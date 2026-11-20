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
     * A cheap fingerprint of [uri]'s contents (its size and a hash of its first and last 64 KB),
     * for noticing that a file has changed. PDF edits are appended to the end, so they change it.
     */
    suspend fun fingerprint(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            pfd.use {
                FileInputStream(it.fileDescriptor).channel.use { channel ->
                    val size = channel.size()
                    digest.update(size.toString().toByteArray())
                    fun hash(at: Long) {
                        val buffer = java.nio.ByteBuffer.allocate(minOf(CHUNK, size - at).toInt())
                        channel.position(at)
                        while (buffer.hasRemaining() && channel.read(buffer) > 0) Unit
                        buffer.flip()
                        digest.update(buffer)
                    }
                    hash(0)
                    if (size > CHUNK) hash(size - CHUNK)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private const val CHUNK = 64L * 1024

    /**
     * Replaces the contents of [uri] with [file]. The current contents are backed up first and
     * put back if writing fails part-way, so the original is never left half-written.
     */
    suspend fun replace(context: Context, uri: Uri, file: File) = withContext(Dispatchers.IO) {
        replaceBlocking(context, uri, file)
    }

    /** [replace] on the calling thread. */
    fun replaceBlocking(context: Context, uri: Uri, file: File) {
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
