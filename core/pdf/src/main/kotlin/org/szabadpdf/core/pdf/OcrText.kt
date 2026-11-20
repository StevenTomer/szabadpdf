package org.szabadpdf.core.pdf

import android.content.Context
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import kotlin.math.hypot

/**
 * Invisible text laid over page images, so OCR'd pages can be searched, selected and copied.
 * Adapted from Tesseract's PDF renderer (src/api/pdfrenderer.cpp, Apache-2.0).
 */
internal object OcrText {

    /** The font's name in page resources. */
    const val FONT_KEY = "SzOcr"

    /** Glyph width of the GlyphLessFont, in ems. */
    private const val GLYPH_WIDTH = 0.5f

    /**
     * Text operators (render mode 3: invisible) for [words]. [toPage] maps a point of the image
     * the words were read from (pixels) to the page's own coordinates, so the page may be
     * scaled, offset or rotated. Each word is sized to its line and stretched to its box.
     */
    fun operators(words: List<OcrWord>, toPage: (Float, Float) -> Pair<Float, Float>): String {
        val f = java.util.Locale.ROOT
        val out = StringBuilder("BT 3 Tr\n")
        for (word in words) {
            val codes = word.text.toCharArray() // UTF-16 code units = CIDs with Identity-H
            if (codes.isEmpty()) continue
            val (ox, oy) = toPage(word.left.toFloat(), word.lineBottom.toFloat())
            val (rx, ry) = toPage(word.right.toFloat(), word.lineBottom.toFloat())
            val (tx, ty) = toPage(word.left.toFloat(), (word.lineBottom - word.lineHeight).toFloat())
            val width = hypot(rx - ox, ry - oy)
            val size = hypot(tx - ox, ty - oy).coerceAtLeast(1f)
            if (width <= 0f) continue
            // Unit vectors along the line and up the page, as the text matrix.
            val ax = (rx - ox) / width
            val ay = (ry - oy) / width
            val ux = (tx - ox) / size
            val uy = (ty - oy) / size
            val stretch = (100f * width / (codes.size * size * GLYPH_WIDTH)).coerceIn(1f, 10_000f)
            val hex = codes.joinToString("") { "%04X".format(it.code) }
            out.append(
                "/$FONT_KEY %.2f Tf %.2f Tz %.5f %.5f %.5f %.5f %.2f %.2f Tm <%s> Tj\n"
                    .format(f, size, stretch, ax, ay, ux, uy, ox, oy, hex),
            )
        }
        out.append("ET\n")
        return out.toString()
    }

    /**
     * Tesseract's "GlyphLessFont": a TrueType font whose glyphs are blank, used as a Type0 font
     * where character codes are UTF-16 code units, all drawn with glyph 1, and mapped straight
     * back to Unicode for text extraction.
     */
    fun font(pdf: PDFDocument, context: Context): PDFObject {
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
            put("FontBBox", pdf.newArray().apply { push(0); push(0); push(glyphUnits); push(1000) })
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
            put("DW", glyphUnits)
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

    /** Glyph width in font units, for the font's descriptor. */
    private val glyphUnits get() = (1000 * GLYPH_WIDTH).toInt()
}
