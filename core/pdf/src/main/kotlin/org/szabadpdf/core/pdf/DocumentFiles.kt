package org.szabadpdf.core.pdf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Writing finished files back to wherever a document came from. */
object DocumentFiles {

    private const val BACKUP_DIR = "backup"

    /**
     * Whether we may overwrite [uri] (some apps that hand us a file only allow reading it).
     * Providers' own "supports write" flag isn't checked: the media store leaves it off for
     * files it will happily write.
     */
    fun canWrite(context: Context, uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let { File(it).canWrite() } == true
        return context.checkUriPermission(
            uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Replaces the contents of [uri] with [file]. The current contents are backed up first and
     * put back if writing fails part-way, so the original is never left half-written.
     */
    suspend fun replace(context: Context, uri: Uri, file: File) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val backup = File(File(context.cacheDir, BACKUP_DIR).apply { mkdirs() }, "backup-${System.nanoTime()}.pdf")
        try {
            val hadContents = runCatching {
                resolver.openInputStream(uri)?.use { input -> backup.outputStream().use { input.copyTo(it) } }
            }.isSuccess && backup.length() > 0

            try {
                write(resolver, uri, file)
                val written = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
                if (written != null && written >= 0 && written != file.length()) {
                    throw IOException("Wrote $written bytes instead of ${file.length()}")
                }
            } catch (e: Exception) {
                if (hadContents) {
                    runCatching { write(resolver, uri, backup) }.onFailure(e::addSuppressed)
                }
                throw e
            }
        } finally {
            backup.delete()
        }
    }

    private fun write(resolver: ContentResolver, uri: Uri, file: File) {
        // "rwt" lets us truncate explicitly: some providers ignore the "t" in "wt" and leave
        // the old tail behind when the new file is shorter.
        val pfd = runCatching { resolver.openFileDescriptor(uri, "rwt") }.getOrNull()
        if (pfd != null) {
            pfd.use {
                FileOutputStream(it.fileDescriptor).channel.use { out ->
                    FileInputStream(file).channel.use { input ->
                        var position = 0L
                        val size = input.size()
                        out.position(0)
                        while (position < size) position += input.transferTo(position, size - position, out)
                        out.truncate(size)
                        out.force(true)
                    }
                }
            }
            return
        }
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write to $uri")
        output.use { out -> file.inputStream().use { it.copyTo(out) } }
    }
}
