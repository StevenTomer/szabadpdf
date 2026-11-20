package org.szabadpdf.core.pdf

import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.SeekableStream
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Lets MuPDF read straight from a file descriptor (for example one handed out by the
 * Storage Access Framework), so the document never has to be copied into memory.
 * Only works for descriptors backed by a regular, seekable file.
 */
internal class FdSeekableInputStream(
    private val pfd: ParcelFileDescriptor,
    private val onClose: () -> Unit = {},
) : SeekableInputStream, Closeable {

    private val input = FileInputStream(pfd.fileDescriptor)
    private val channel = input.channel

    override fun read(buf: ByteArray): Int = channel.read(ByteBuffer.wrap(buf))

    override fun seek(offset: Long, whence: Int): Long {
        val target = when (whence) {
            SeekableStream.SEEK_SET -> offset
            SeekableStream.SEEK_CUR -> channel.position() + offset
            SeekableStream.SEEK_END -> channel.size() + offset
            else -> throw IllegalArgumentException("Unknown seek mode $whence")
        }
        channel.position(target)
        return target
    }

    override fun position(): Long = channel.position()

    override fun close() {
        try {
            input.close()
            pfd.close()
        } finally {
            onClose()
        }
    }
}
