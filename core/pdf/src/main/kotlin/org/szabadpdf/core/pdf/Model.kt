package org.szabadpdf.core.pdf

/** Page size in PDF points (1/72 inch), after the page's own rotation is applied. */
data class PageSize(val width: Float, val height: Float)

/** A rectangle in PDF points, measured from the page's top-left corner. */
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Where a tapped link leads. */
sealed interface LinkTarget {
    data class Page(val index: Int) : LinkTarget
    data class External(val uri: String) : LinkTarget
}

/** One table-of-contents entry, flattened. [pageIndex] is -1 when the entry has no page in this document. */
data class OutlineEntry(val title: String, val depth: Int, val pageIndex: Int)

/**
 * One page of a document being rebuilt by [PdfDocument.saveRearranged]. [rotation] is extra
 * clockwise rotation in degrees on top of the page's own, any multiple of 90 (negative is fine).
 */
sealed interface PageSpec {
    val rotation: Int

    /**
     * Page [index] of [from], or of the document being saved when [from] is null. [flipH] mirrors
     * it left to right and [flipV] top to bottom, as the page looks before the extra [rotation].
     */
    data class Existing(
        val index: Int,
        val from: PdfDocument? = null,
        override val rotation: Int = 0,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
    ) : PageSpec

    data class Blank(val size: PageSize, override val rotation: Int = 0) : PageSpec
}

/** A point in PDF points, measured from the page's top-left corner. */
data class PdfPoint(val x: Float, val y: Float)

/** How ink is drawn: [color] is 0xRRGGBB, [width] in points. */
data class InkStyle(val color: Int, val width: Float, val opacity: Float = 1f)

enum class MarkupKind { HIGHLIGHT, UNDERLINE, STRIKEOUT }

/** Selected text on one page, between [start] and [end] in reading order. */
data class TextSelection(
    val page: Int,
    val start: PdfPoint,
    val end: PdfPoint,
    val rects: List<PdfRect>,
    val text: String,
)

enum class AnnotationKind { NOTE, HIGHLIGHT, UNDERLINE, STRIKEOUT, INK, TEXT_BOX, SHAPE, OTHER }

enum class ShapeKind { RECTANGLE, ELLIPSE, LINE, ARROW }

/** An annotation on a page. [id] is its PDF object number, stable while the document is open. */
data class AnnotationInfo(val id: Int, val kind: AnnotationKind, val bounds: PdfRect, val contents: String)

data class EditState(val canUndo: Boolean = false, val canRedo: Boolean = false)

/**
 * A word read from a page image by OCR, with its box in the image's pixels. [lineHeight] and
 * [lineBottom] are its text line's, so all words of a line get one size and baseline.
 */
data class OcrWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val lineHeight: Int,
    val lineBottom: Int,
)

enum class FieldKind { TEXT, CHECKBOX, RADIO, CHOICE, SIGNATURE, BUTTON, OTHER }

/**
 * A form field's widget on a page. [id] is the widget's object number. [checked] is for
 * checkboxes and radio buttons (this particular button); [options] for dropdowns and lists.
 */
data class FormField(
    val id: Int,
    val kind: FieldKind,
    val name: String,
    val bounds: PdfRect,
    val value: String,
    val checked: Boolean,
    val options: List<String>,
    val multiline: Boolean,
    val readOnly: Boolean,
)

/** The document's own description of itself (PDF "Info"); blank where it has none. */
data class DocumentProperties(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
)

enum class TextFont { SANS, SERIF, MONO }

/**
 * How a text box looks: the text's [font], [size] (points), weight, slant, underline and
 * [color]; an optional border ([borderWidth] points, [borderColor]); and an optional
 * background fill (otherwise the page shows through). Colours are 0xRRGGBB.
 */
data class TextBoxStyle(
    val font: TextFont = TextFont.SANS,
    val size: Float = 14f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Int = 0x000000,
    val border: Boolean = false,
    val borderWidth: Float = 1f,
    val borderColor: Int = 0x000000,
    val background: Boolean = false,
    val backgroundColor: Int = 0xFFFFFF,
)

/**
 * How a rectangle, oval, line or arrow looks: its outline ([border], [borderWidth] points,
 * [borderColor]) and, for rectangles and ovals, an optional [fill]. Lines always show their outline.
 */
data class ShapeStyle(
    val border: Boolean = true,
    val borderColor: Int = 0x000000,
    val borderWidth: Float = 1.5f,
    val fill: Boolean = false,
    val fillColor: Int = 0xFFF59D,
)
