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
            val font = if (text.any { it.isNotEmpty() }) glyphLessFont(pdf, context) else null
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
                        resources.put("Font", pdf.newDictionary().apply { put("Ocr", font) })
                        textLayer(contents, words, scale, pageH)
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

    /**
     * Invisible text (render mode 3) for [words], each stretched over its box in the image.
     * Adapted from Tesseract's PDF renderer (src/api/pdfrenderer.cpp, Apache-2.0): every glyph
     * of the font is half an em wide, so the horizontal scale fits a word's characters to its box.
     */
    private fun textLayer(out: StringBuilder, words: List<OcrWord>, pointsPerPixel: Float, pageHeight: Float) {
        val f = java.util.Locale.ROOT
        out.append("BT 3 Tr\n")
        for (word in words) {
            val codes = word.text.toCharArray() // UTF-16 code units = CIDs with Identity-H
            if (codes.isEmpty()) continue
            val size = (word.lineHeight * pointsPerPixel).coerceAtLeast(1f)
            val width = (word.right - word.left) * pointsPerPixel
            val stretch = (100f * width / (codes.size * size * GLYPH_WIDTH)).coerceIn(1f, 10_000f)
            val x = word.left * pointsPerPixel
            val y = pageHeight - word.lineBottom * pointsPerPixel
            val hex = codes.joinToString("") { "%04X".format(it.code) }
            out.append("/Ocr %.2f Tf %.2f Tz 1 0 0 1 %.2f %.2f Tm <%s> Tj\n".format(f, size, stretch, x, y, hex))
        }
        out.append("ET\n")
    }

    /**
     * Tesseract's "GlyphLessFont": a TrueType font whose glyphs are blank, used as a Type0 font
     * where character codes are UTF-16 code units, all drawn with glyph 1, and mapped straight
     * back to Unicode for text extraction.
     */
    private fun glyphLessFont(pdf: PDFDocument, context: Context): PDFObject {
        val fontBytes = context.assets.open("ocr/GlyphLessFont.ttf").use { it.readBytes() }
        val fontFile = pdf.addStream(Buffer().apply { writeBytes(fontBytes) })
        fontFile.put("Length1", fontBytes.size)

        // Every CID (0..65535) uses glyph 1.
        val cidToGid = ByteArray(2 * 65536) { if (it % 2 == 1) 1 else 0 }
        val cidToGidMap = pdf.addStream(Buffer().apply { writeBytes(cidToGid) })

        val toUnicode = pdf.addStream(
            """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
            /CMapName /Adobe-Identify-UCS def
            /CMapType 2 def
            1 begincodespacerange
            <0000> <FFFF>
            endcodespacerange
            1 beginbfrange
            <0000> <FFFF> <0000>
            endbfrange
            endcmap
            CMapName currentdict /CMap defineresource pop
            end
            end
            """.trimIndent(),
        )

        val descriptor = pdf.newDictionary().apply {
            put("Type", pdf.newName("FontDescriptor"))
            put("FontName", pdf.newName("GlyphLessFont"))
            put("Flags", 5)
            put("FontBBox", pdf.newArray().apply { push(0); push(0); push((1000 * GLYPH_WIDTH).toInt()); push(1000) })
            put("ItalicAngle", 0)
            put("Ascent", 1000)
            put("Descent", 0)
            put("CapHeight", 1000)
            put("StemV", 80)
            put("FontFile2", fontFile)
        }
        val cidFont = pdf.newDictionary().apply {
            put("Type", pdf.newName("Font"))
            put("Subtype", pdf.newName("CIDFontType2"))
            put("BaseFont", pdf.newName("GlyphLessFont"))
            put("CIDSystemInfo", pdf.newDictionary().apply {
                put("Registry", pdf.newString("Adobe"))
                put("Ordering", pdf.newString("Identity"))
                put("Supplement", 0)
            })
            put("FontDescriptor", pdf.addObject(descriptor))
            put("DW", (1000 * GLYPH_WIDTH).toInt())
            put("CIDToGIDMap", cidToGidMap)
        }
        val type0 = pdf.newDictionary().apply {
            put("Type", pdf.newName("Font"))
            put("Subtype", pdf.newName("Type0"))
            put("BaseFont", pdf.newName("GlyphLessFont"))
            put("Encoding", pdf.newName("Identity-H"))
            put("DescendantFonts", pdf.newArray().apply { push(pdf.addObject(cidFont)) })
            put("ToUnicode", toUnicode)
        }
        return pdf.addObject(type0)
    }

    /** Glyph width of the GlyphLessFont, in ems. */
    private const val GLYPH_WIDTH = 0.5f
}
