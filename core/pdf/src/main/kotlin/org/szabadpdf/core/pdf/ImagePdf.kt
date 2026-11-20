package org.szabadpdf.core.pdf

import android.content.Context
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Rect
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.min

/** Builds a PDF out of page images, e.g. from the scanner. */
object ImagePdf {

    private const val A4_SHORT = 595f
    private const val A4_LONG = 842f

    /**
     * Writes one page per image (JPEG or PNG files) to [target]. JPEGs are embedded as they are,
     * without re-compressing. Each page fits an A4 sheet in the image's orientation, keeping the
     * image's proportions.
     *
     * [text], if given, holds the words read from each image by OCR (same order as [images]);
     * they're laid over the picture as invisible text, so the page can be searched and its text
     * selected and copied.
     */
    suspend fun write(
        context: Context,
        images: List<File>,
        target: File,
        title: String? = null,
        text: List<List<OcrWord>> = emptyList(),
    ) = withContext(PdfEngine.dispatcher) {
        require(images.isNotEmpty()) { "No pages to write" }
        val pdf = PDFDocument()
        try {
            val font = if (text.any { it.isNotEmpty() }) OcrText.font(pdf, context) else null
            for ((pageIndex, file) in images.withIndex()) {
                val image = Image(file.path)
                try {
                    val w = image.width.toFloat()
                    val h = image.height.toFloat()
                    val (boxW, boxH) = if (w > h) A4_LONG to A4_SHORT else A4_SHORT to A4_LONG
                    val scale = min(boxW / w, boxH / h)
                    val pageW = w * scale
                    val pageH = h * scale
                    val resources = pdf.newDictionary()
                    val xObjects = pdf.newDictionary()
                    xObjects.put("Im0", pdf.addImage(image))
                    resources.put("XObject", xObjects)
                    val contents = StringBuilder("q %.3f 0 0 %.3f 0 0 cm /Im0 Do Q\n".format(java.util.Locale.ROOT, pageW, pageH))
                    val words = text.getOrNull(pageIndex).orEmpty()
                    if (font != null && words.isNotEmpty()) {
                        resources.put("Font", pdf.newDictionary().apply { put(OcrText.FONT_KEY, font) })
                        // Image pixels to page space: scaled, with y running up from the bottom.
                        contents.append(OcrText.operators(words) { x, y -> scale * x to pageH - scale * y })
                    }
                    pdf.insertPage(-1, pdf.addPage(Rect(0f, 0f, pageW, pageH), 0, resources, contents.toString()))
                } finally {
                    image.destroy()
                }
            }
            if (title != null) pdf.setMetaData(Document.META_INFO_TITLE, title)
            target.parentFile?.mkdirs()
            pdf.save(target.path, "compress")
        } finally {
            pdf.destroy()
        }
        val check = Document.openDocument(target.path)
        try {
            if (check.countPages() != images.size) throw IOException("The saved file didn't come out right")
        } finally {
            check.destroy()
        }
    }

}
