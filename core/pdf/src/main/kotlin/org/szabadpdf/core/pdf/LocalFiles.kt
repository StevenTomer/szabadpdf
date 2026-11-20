package org.szabadpdf.core.pdf

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Files on the device's own storage. With "All files access" (Android 11+) or the storage
 * permission (older Android), SzabadPDF can list folders and keep using a file by its path,
 * which, unlike the temporary access other apps lend, lasts across restarts.
 */
object LocalFiles {

    /** Whether folders can be listed and files opened by path. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** Whether [uri] can be read right now. */
    fun canRead(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /**
     * The file on this device behind [uri], when there is one and it can be read: a file:// URI,
     * or a document from the storage, downloads or media providers.
     */
    fun fileFor(context: Context, uri: Uri): File? {
        // Access lent by another app also opens the file's path, but only until the app
        // restarts: a path is only worth keeping with file access of our own.
        if (!hasAccess(context)) return null
        val file = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            "content" -> runCatching { contentFile(context, uri) }.getOrNull()
            else -> null
        }
        // canRead() can say yes when opening is still refused (scoped storage), so really try.
        return file?.takeIf { it.isFile && runCatching { it.inputStream().close() }.isSuccess }
    }

    private fun contentFile(context: Context, uri: Uri): File? {
        val authority = uri.authority
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val id = DocumentsContract.getDocumentId(uri)
            when (authority) {
                "com.android.externalstorage.documents" -> {
                    val volume = id.substringBefore(':')
                    val path = id.substringAfter(':', "")
                    val root = if (volume.equals("primary", ignoreCase = true)) {
                        Environment.getExternalStorageDirectory()
                    } else {
                        File("/storage/$volume")
                    }
                    return File(root, path)
                }
                "com.android.providers.downloads.documents" -> {
                    if (id.startsWith("raw:")) return File(id.removePrefix("raw:"))
                    if (id.startsWith("msf:")) {
                        return mediaPath(context, MediaStore.Files.getContentUri("external"), id.removePrefix("msf:"))
                    }
                }
                "com.android.providers.media.documents" -> {
                    val number = id.substringAfter(':', "")
                    if (number.isNotEmpty()) return mediaPath(context, MediaStore.Files.getContentUri("external"), number)
                }
            }
        }
        // Media store and many other providers still say where their files are.
        return dataColumn(context, uri)?.let(::File)
    }

    private fun mediaPath(context: Context, collection: Uri, id: String): File? =
        id.toLongOrNull()?.let { dataColumn(context, android.content.ContentUris.withAppendedId(collection, it)) }?.let(::File)

    @Suppress("DEPRECATION")
    private fun dataColumn(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    /** The main places to start browsing from: internal storage, Download, Documents, and any SD cards. */
    fun roots(context: Context): List<Pair<String, File>> = buildList {
        val main = Environment.getExternalStorageDirectory()
        add("Download" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        add("Documents" to Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
        add("Internal storage" to main)
        // Removable cards: the storage roots other than the main one.
        context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { appDir ->
            val root = appDir.path.substringBefore("/Android/").let(::File)
            if (root.canRead()) add("SD card" to root)
        }
    }
}
