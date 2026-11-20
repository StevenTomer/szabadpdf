package org.szabadpdf.core.pdf

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.artifex.mupdf.fitz.DisplayList
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFGraftMap
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.roundToInt

/**
 * An open document. All work runs on the single MuPDF thread (see [PdfEngine]).
 *
 * Coordinates handed in and out are PDF points measured from the page's top-left corner,
 * regardless of where the page's media box actually starts.
 */
class PdfDocument private constructor(
    private val doc: Document,
    private val source: Closeable,
) : Closeable {

    @Volatile
    private var closed = false

    @Volatile
    private var bounds: List<Rect> = emptyList()

    /** True until the right password has been given, for encrypted documents. */
    @Volatile
    var isLocked: Boolean = true
        private set

    /** Kept to check files saved from an encrypted document, which stay encrypted. */
    private var password: String? = null

    val pageCount: Int get() = bounds.size

    val pageSizes: List<PageSize>
        get() = bounds.map { PageSize(it.x1 - it.x0, it.y1 - it.y0) }

    /** Recently used pages, pre-parsed so re-rendering at a new zoom level is fast. */
    private val displayLists = object : LruCache<Int, DisplayList>(DISPLAY_LIST_CACHE_SIZE) {
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: DisplayList, newValue: DisplayList?) {
            oldValue.destroy()
        }
    }

    /** Returns false if the password is wrong. */
    suspend fun authenticate(password: String): Boolean = onEngine {
        val ok = doc.authenticatePassword(password)
        if (ok) {
            this.password = password
            loadLayout()
        }
        ok
    }

    suspend fun properties(): DocumentProperties = onEngine {
        fun get(key: String) = doc.getMetaData(key).orEmpty().trim()
        DocumentProperties(get(Document.META_INFO_TITLE), get(Document.META_INFO_AUTHOR), get(INFO_SUBJECT), get(INFO_KEYWORDS))
    }

    /** Changes the title, author, subject and keywords; one undoable step. */
    suspend fun setProperties(properties: DocumentProperties): EditState = onEngine {
        editDocument("Document properties") {
            doc.setMetaData(Document.META_INFO_TITLE, properties.title.trim())
            doc.setMetaData(Document.META_INFO_AUTHOR, properties.author.trim())
            doc.setMetaData(INFO_SUBJECT, properties.subject.trim())
            doc.setMetaData(INFO_KEYWORDS, properties.keywords.trim())
        }
    }

    suspend fun title(): String? = onEngine {
        doc.getMetaData(Document.META_INFO_TITLE)?.takeIf { it.isNotBlank() }
    }

    /** Renders a whole page [width] pixels wide. */
    suspend fun renderPage(index: Int, width: Int): Bitmap = onEngine {
        val size = bounds[index].let { PageSize(it.x1 - it.x0, it.y1 - it.y0) }
        val height = (width * size.height / size.width).roundToInt().coerceAtLeast(1)
        draw(index, width, height, 0, 0, width, height)
    }

    /**
     * Renders part of a page. The page is scaled to [fullWidth] x [fullHeight] pixels and the
     * [width] x [height] patch starting at ([x], [y]) is returned. Used for sharp zoomed views
     * without ever allocating a bitmap for the whole enlarged page.
     */
    suspend fun renderRegion(
        index: Int,
        fullWidth: Int,
        fullHeight: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Bitmap = onEngine { draw(index, fullWidth, fullHeight, x, y, width, height) }

    /** Each hit is one or more rectangles (a hit can wrap across lines). Matching ignores case. */
    suspend fun search(index: Int, query: String): List<List<PdfRect>> = onEngine {
        val origin = bounds[index]
        displayList(index).search(query).orEmpty().map { hit ->
            hit.map { quad ->
                val r = quad.toRect()
                PdfRect(r.x0 - origin.x0, r.y0 - origin.y0, r.x1 - origin.x0, r.y1 - origin.y0)
            }
        }
    }

    /** The link under a point on a page, if any. */
    suspend fun linkAt(index: Int, x: Float, y: Float): LinkTarget? = onEngine {
        val origin = bounds[index]
        val page = doc.loadPage(index)
        val links = page.links.orEmpty()
        try {
            val link = links.firstOrNull { it.bounds.contains(x + origin.x0, y + origin.y0) }
            link?.let(::resolve)
        } finally {
            links.forEach(Link::destroy)
            page.destroy()
        }
    }

    suspend fun outline(): List<OutlineEntry> = onEngine {
        val result = mutableListOf<OutlineEntry>()
        fun walk(items: Array<Outline>, depth: Int) {
            for (item in items) {
                val page = runCatching {
                    val uri = item.uri
                    if (uri == null || Link.isExternal(uri)) -1
                    else doc.pageNumberFromLocation(doc.resolveLink(item))
                }.getOrDefault(-1)
                result += OutlineEntry(item.title.orEmpty(), depth, page)
                item.down?.let { walk(it, depth + 1) }
            }
        }
        doc.loadOutline()?.let { walk(it, 0) }
        result
    }

    /** Whether this is a PDF (rather than another format MuPDF reads), so its pages can be edited. */
    val isEditable: Boolean get() = doc is PDFDocument

    /**
     * Writes a copy of this document to [target] with its pages replaced by [pages], in that
     * order: reordered, rotated, dropped, blank or taken from other open documents. Everything
     * else (metadata, bookmarks to pages that remain, forms) is kept.
     *
     * Each of this document's own pages can appear once; for more copies (duplicating pages),
     * take them from a second instance of the same file via [PageSpec.Existing.from]. Pages from
     * other documents keep their annotations (except links and form fields).
     *
     * The rebuild happens to this document in memory, so it should be a fresh instance opened
     * only for saving and closed afterwards. Documents named in [PageSpec.Existing.from] aren't
     * saved, but their annotations are unhooked from their pages in memory while being copied,
     * so they should also be instances opened for this purpose (or not used for editing after).
     * The written file is reopened and checked before this returns.
     */
    suspend fun saveRearranged(pages: List<PageSpec>, target: File) = onEngine {
        require(pages.isNotEmpty()) { "A document needs at least one page" }
        val pdf = doc as? PDFDocument ?: throw UnsupportedOperationException("Only PDF files can be edited")
        val graftMaps = HashMap<PdfDocument, PDFGraftMap>()
        try {
            // New pages go at the end first; then one rearrange puts everything in place.
            var next = pdf.countPages()
            val order = IntArray(pages.size) { i ->
                when (val spec = pages[i]) {
                    is PageSpec.Existing -> {
                        val from = spec.from
                        if (from == null || from === this) {
                            spec.index
                        } else {
                            val src = from.doc as? PDFDocument
                                ?: throw UnsupportedOperationException("Only pages from PDF files can be inserted")
                            check(!from.closed) { "Source document is closed" }
                            val map = graftMaps.getOrPut(from) { pdf.newPDFGraftMap() }
                            map.graftPage(-1, src, spec.index)
                            copyAnnotations(src, spec.index, pdf.findPage(next))
                            next++
                        }
                    }
                    is PageSpec.Blank -> {
                        val page = pdf.addPage(Rect(0f, 0f, spec.size.width, spec.size.height), 0, pdf.newDictionary(), "")
                        pdf.insertPage(-1, page)
                        next++
                    }
                }
            }
            require(order.distinct().size == order.size) {
                "A page of this document can't appear twice; take copies from a second instance"
            }
            pdf.rearrangePages(order)

            pages.forEachIndexed { i, spec ->
                if (spec is PageSpec.Existing && (spec.flipH || spec.flipV)) flip(pdf, pdf.findPage(i), spec.flipH, spec.flipV)
                if (spec.rotation % 360 == 0) return@forEachIndexed
                val page = pdf.findPage(i)
                val current = page.getInheritable("Rotate").let { if (it.isNull) 0 else it.asInteger() }
                page.put("Rotate", Math.floorMod(current + spec.rotation, 360))
            }

            target.parentFile?.mkdirs()
            pdf.save(target.path, "garbage=compact,compress")
        } finally {
            graftMaps.values.forEach(PDFGraftMap::destroy)
            // The in-memory document no longer matches what was laid out.
            displayLists.evictAll()
            runCatching { loadLayout() }
        }

        verifySaved(target, pages.size)
    }

    /**
     * Mirrors [page] (left to right when [horizontal], top to bottom when [vertical], as it
     * looks with its own rotation): its content, and its annotations' positions and appearances.
     */
    private fun flip(pdf: PDFDocument, page: PDFObject, horizontal: Boolean, vertical: Boolean) {
        // Flips are in the page's own coordinates, which a quarter turn swaps.
        val rotate = page.getInheritable("Rotate").let { if (it.isNull) 0 else Math.floorMod(it.asInteger(), 360) }
        val (h, v) = if (rotate % 180 == 90) vertical to horizontal else horizontal to vertical
        val box = page.getInheritable("CropBox").takeIf { it.size() == 4 } ?: page.getInheritable("MediaBox")
        if (box.size() != 4) return
        val xs = listOf(box.get(0).asFloat(), box.get(2).asFloat())
        val ys = listOf(box.get(1).asFloat(), box.get(3).asFloat())
        val m = mirror(h, v, xs.min() + xs.max(), ys.min() + ys.max())

        val contents = page.get("Contents")
        if (!contents.isNull) {
            val all = pdf.newArray()
            all.push(pdf.addStream("q ${m.joinToString(" ") { fmt(it) }} cm\n"))
            if (contents.size() > 0 && contents.isArray) for (i in 0 until contents.size()) all.push(contents.get(i))
            else all.push(contents)
            all.push(pdf.addStream("\nQ\n"))
            page.put("Contents", all)
        }

        val annots = page.get("Annots")
        for (i in 0 until annots.size()) {
            val annot = annots.get(i)
            if (annot.isNull) continue
            annot.get("Rect").let { if (it.size() == 4) annot.put("Rect", boxArray(pdf, transformBox(floats(it), m))) }
            for (key in listOf("QuadPoints", "L", "Vertices", "CL")) {
                annot.get(key).let { if (it.size() >= 2) annot.put(key, pointArray(pdf, floats(it), m)) }
            }
            annot.get("InkList").let { ink ->
                if (ink.size() == 0) return@let
                val strokes = pdf.newArray()
                for (s in 0 until ink.size()) strokes.push(pointArray(pdf, floats(ink.get(s)), m))
                annot.put("InkList", strokes)
            }
            val ap = annot.get("AP")
            for (key in listOf("N", "R", "D")) {
                val look = ap.get(key)
                if (look.isNull) continue
                if (look.isStream) flipAppearance(pdf, look, h, v)
                else for (state in look) look.get(state).takeIf { it.isStream }?.let { flipAppearance(pdf, it, h, v) }
            }
        }
    }

    /**
     * Mirrors an annotation appearance in place, about the middle of where it's drawn, so that
     * once fitted into the mirrored Rect it lands mirrored like the page.
     */
    private fun flipAppearance(pdf: PDFDocument, form: PDFObject, h: Boolean, v: Boolean) {
        val bbox = form.get("BBox").takeIf { it.size() == 4 }?.let(::floats) ?: return
        val matrix = form.get("Matrix").takeIf { it.size() == 6 }?.let(::floats) ?: floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        val shown = transformBox(bbox, matrix)
        val g = mirror(h, v, shown[0] + shown[2], shown[1] + shown[3])
        form.put("Matrix", pdf.newArray().also { a -> concat(matrix, g).forEach { a.push(it) } })
    }

    // --- text selection ---------------------------------------------------------------------

    /** Selects the word at [point], or returns null if there's no text there. */
    suspend fun selectWord(index: Int, point: PdfPoint): TextSelection? = onEngine {
        val p = toPage(index, point)
        val st = text(index)
        // Snapping finds the nearest word even from blank paper, so first check there's a line here.
        val onText = st.blocks.orEmpty().any { block ->
            block.lines.orEmpty().any { line -> line.bbox.let { p.x >= it.x0 && p.x <= it.x1 && p.y >= it.y0 && p.y <= it.y1 } }
        }
        if (!onText) return@onEngine null
        val quad = st.snapSelection(Point(p), Point(p), StructuredText.SELECT_WORDS)
        val r = quad.toRect()
        if (r.isEmpty || r.x1 - r.x0 < 0.1f) return@onEngine null
        val midLeft = (quad.ul_y + quad.ll_y) / 2f
        val midRight = (quad.ur_y + quad.lr_y) / 2f
        select(index, fromPage(index, quad.ul_x, midLeft), fromPage(index, quad.ur_x, midRight))
    }

    /** Selects the text running from [start] to [end], or returns null if there's none. */
    suspend fun selectText(index: Int, start: PdfPoint, end: PdfPoint): TextSelection? =
        onEngine { select(index, start, end) }

    // --- annotations -----------------------------------------------------------------------

    /** Undo history is kept from the first edit on. Only PDFs can be annotated. */
    suspend fun editState(): EditState = onEngine { currentEditState() }

    suspend fun annotations(index: Int): List<AnnotationInfo> = onEngine {
        val page = doc.loadPage(index) as? PDFPage ?: return@onEngine emptyList()
        try {
            page.annotations.orEmpty().map { it.info(index) }
        } finally {
            page.destroy()
        }
    }

    /** The topmost annotation whose bounds contain [point] (with [slop] points to spare). */
    suspend fun annotationAt(index: Int, point: PdfPoint, slop: Float = 4f): AnnotationInfo? =
        annotations(index).lastOrNull {
            point.x >= it.bounds.left - slop && point.x <= it.bounds.right + slop &&
                point.y >= it.bounds.top - slop && point.y <= it.bounds.bottom + slop
        }

    /** Adds one freehand stroke as an ink annotation. */
    suspend fun addInk(index: Int, points: List<PdfPoint>, style: InkStyle): EditState = addInk(index, listOf(points), style, "Ink")

    /** Adds several strokes as one ink annotation (e.g. a signature), undone as one. */
    suspend fun addInk(index: Int, strokes: List<List<PdfPoint>>, style: InkStyle, label: String): EditState = onEngine {
        require(strokes.isNotEmpty() && strokes.all { it.isNotEmpty() })
        edit(index, label) { page ->
            val annot = page.createAnnotation(PDFAnnotation.TYPE_INK)
            annot.setInkList(strokes.map { stroke -> stroke.map { toPage(index, it) }.toTypedArray() }.toTypedArray())
            annot.setColor(rgb(style.color))
            annot.setBorderWidth(style.width)
            if (style.opacity < 1f) annot.setOpacity(style.opacity)
            annot.update()
        }
    }

    /** Highlights, underlines or strikes out the text from [start] to [end]. */
    suspend fun addMarkup(index: Int, start: PdfPoint, end: PdfPoint, kind: MarkupKind, color: Int): EditState =
        onEngine {
            val quads = text(index).highlight(toPage(index, start), toPage(index, end)).orEmpty()
            if (quads.isEmpty()) return@onEngine currentEditState()
            edit(index, kind.name) { page ->
                val type = when (kind) {
                    MarkupKind.HIGHLIGHT -> PDFAnnotation.TYPE_HIGHLIGHT
                    MarkupKind.UNDERLINE -> PDFAnnotation.TYPE_UNDERLINE
                    MarkupKind.STRIKEOUT -> PDFAnnotation.TYPE_STRIKE_OUT
                }
                val annot = page.createAnnotation(type)
                annot.setQuadPoints(quads)
                annot.setColor(rgb(color))
                annot.update()
            }
        }

    /** Adds a sticky note with its icon's top-left corner at [point]. */
    suspend fun addNote(index: Int, point: PdfPoint, text: String): EditState = onEngine {
        edit(index, "Note") { page ->
            val p = toPage(index, point)
            val annot = page.createAnnotation(PDFAnnotation.TYPE_TEXT)
            annot.setRect(Rect(p.x, p.y, p.x + NOTE_SIZE, p.y + NOTE_SIZE))
            annot.setIcon("Note")
            annot.setColor(rgb(NOTE_COLOR))
            annot.setContents(text)
            annot.update()
        }
    }

    /** Adds a text box with its top-left corner at [at], sized to fit [text] (kept within the page). */
    suspend fun addTextBox(index: Int, at: PdfPoint, text: String, style: TextBoxStyle): EditState = onEngine {
        val pdf = doc as? PDFDocument ?: throw UnsupportedOperationException("Only PDF files can be annotated")
        edit(index, "Text box") { page ->
            val annot = page.createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
            writeTextBox(pdf, index, annot, toPage(index, at), text, style)
        }
    }

    /** Changes text box [id]'s text and look; its top-left corner stays where it is. */
    suspend fun updateTextBox(index: Int, id: Int, text: String, style: TextBoxStyle): EditState = onEngine {
        val pdf = doc as? PDFDocument ?: throw UnsupportedOperationException("Only PDF files can be annotated")
        edit(index, "Edit text box") { page ->
            val annot = find(page, id) ?: return@edit
            val r = annot.rect
            writeTextBox(pdf, index, annot, Point(minOf(r.x0, r.x1), minOf(r.y0, r.y1)), text, style)
        }
    }

    /** How text box [id] looks (as SzabadPDF made it; other text boxes get the defaults, at their size). */
    suspend fun textBoxStyle(index: Int, id: Int): TextBoxStyle? = onEngine {
        val page = doc.loadPage(index) as? PDFPage ?: return@onEngine null
        try {
            val annot = find(page, id) ?: return@onEngine null
            readTextBoxStyle(annot)
        } finally {
            page.destroy()
        }
    }

    /** Adds a rectangle, ellipse, line or arrow from [start] to [end]. */
    suspend fun addShape(index: Int, kind: ShapeKind, start: PdfPoint, end: PdfPoint, style: ShapeStyle): EditState = onEngine {
        edit(index, "Shape") { page ->
            val a = toPage(index, start)
            val b = toPage(index, end)
            val closed = kind == ShapeKind.RECTANGLE || kind == ShapeKind.ELLIPSE
            val annot = if (closed) {
                page.createAnnotation(if (kind == ShapeKind.RECTANGLE) PDFAnnotation.TYPE_SQUARE else PDFAnnotation.TYPE_CIRCLE).apply {
                    setRect(Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y)))
                    if (style.fill) setInteriorColor(rgb(style.fillColor))
                }
            } else {
                page.createAnnotation(PDFAnnotation.TYPE_LINE).apply {
                    setLine(a, b)
                    if (kind == ShapeKind.ARROW) setLineEndingStyles(PDFAnnotation.LINE_ENDING_NONE, PDFAnnotation.LINE_ENDING_OPEN_ARROW)
                }
            }
            if (style.border || !closed) {
                annot.setColor(rgb(style.borderColor))
                annot.setBorderWidth(style.borderWidth)
            } else {
                // No outline: no stroke colour, so only the fill is drawn.
                annot.setColor(FloatArray(0))
                annot.setBorderWidth(0f)
            }
            annot.update()
        }
    }

    suspend fun setContents(index: Int, id: Int, text: String): EditState = onEngine {
        edit(index, "Edit note") { page ->
            find(page, id)?.let {
                it.setContents(text)
                it.update()
            }
        }
    }

    suspend fun deleteAnnotation(index: Int, id: Int): EditState = onEngine {
        edit(index, "Delete") { page -> find(page, id)?.let(page::deleteAnnotation) }
    }

    /**
     * Rubs out the parts of ink strokes within [radius] points of the eraser path [points], the
     * way a real eraser does: a stroke crossed in the middle becomes two. Calls with the same
     * [gesture] are undone together. Returns null if nothing was erased.
     */
    suspend fun erase(index: Int, points: List<PdfPoint>, radius: Float, gesture: Int): EditState? = onEngine {
        val eraser = points.map { toPage(index, it) }
        if (eraser.isEmpty()) return@onEngine null
        val page = doc.loadPage(index) as? PDFPage ?: return@onEngine null
        // Annotation -> its strokes after erasing (empty: nothing left of it).
        val changes = try {
            page.annotations.orEmpty().filter { it.type == PDFAnnotation.TYPE_INK }.mapNotNull { annot ->
                val reach = radius + annot.borderWidth / 2f
                val box = annot.bounds
                if (eraser.none { it.x >= box.x0 - reach && it.x <= box.x1 + reach && it.y >= box.y0 - reach && it.y <= box.y1 + reach }) {
                    return@mapNotNull null
                }
                var changed = false
                val strokes = annot.inkList.orEmpty().flatMap { stroke ->
                    val pieces = rubOut(stroke.toList(), eraser, reach)
                    if (pieces == null) listOf(stroke.toList()) else {
                        changed = true
                        pieces
                    }
                }
                if (changed) annot.`object`.asIndirect() to strokes else null
            }
        } finally {
            page.destroy()
        }
        if (changes.isEmpty()) return@onEngine null
        edit(index, "Erase", group = "Erase#$gesture") { p ->
            for ((id, strokes) in changes) {
                val annot = find(p, id) ?: continue
                if (strokes.isEmpty()) {
                    p.deleteAnnotation(annot)
                } else {
                    annot.setInkList(strokes.map { it.toTypedArray() }.toTypedArray())
                    annot.update()
                }
            }
        }
    }

    /**
     * What's left of [stroke] after rubbing out everything within [reach] of the [eraser] path,
     * as separate pieces; or null if the eraser didn't touch it.
     */
    private fun rubOut(stroke: List<Point>, eraser: List<Point>, reach: Float): List<List<Point>>? {
        if (stroke.isEmpty()) return null
        // Points close enough together that the eraser can't slip between them.
        val step = (reach / 2f).coerceAtLeast(0.25f)
        val dense = ArrayList<Point>(stroke.size * 2)
        dense += stroke[0]
        for (k in 1 until stroke.size) {
            val a = stroke[k - 1]
            val b = stroke[k]
            val length = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            val parts = kotlin.math.ceil(length / step).toInt().coerceAtLeast(1)
            for (n in 1..parts) {
                val t = n.toFloat() / parts
                dense += Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
        fun rubbed(p: Point): Boolean {
            if (eraser.size == 1) return kotlin.math.hypot(p.x - eraser[0].x, p.y - eraser[0].y) <= reach
            for (e in 1 until eraser.size) if (pointToSegment(p, eraser[e - 1], eraser[e]) <= reach) return true
            return false
        }
        val gone = dense.map(::rubbed)
        if (gone.none { it }) return null
        val pieces = mutableListOf<List<Point>>()
        var run = mutableListOf<Point>()
        dense.forEachIndexed { k, p ->
            if (gone[k]) {
                if (run.size >= 2) pieces += run
                run = mutableListOf()
            } else run += p
        }
        if (run.size >= 2) pieces += run
        return pieces
    }

    private fun pointToSegment(p: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSq).coerceIn(0f, 1f)
        return kotlin.math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    // --- recognizing text on existing pages -------------------------------------------------

    /** Whether page [index] has any text (pages that are only a picture have none). */
    suspend fun hasText(index: Int): Boolean = onEngine { text(index).asText().any { !it.isWhitespace() } }

    /**
     * Lays [words], read by OCR from a render of page [index] that was [imageWidth] pixels wide,
     * over the page as invisible text. Calls with the same [group] undo together.
     */
    suspend fun addTextLayer(context: Context, index: Int, words: List<OcrWord>, imageWidth: Int, group: String): EditState =
        onEngine {
            if (words.isEmpty()) return@onEngine currentEditState()
            val pdf = doc as? PDFDocument ?: throw UnsupportedOperationException("Only PDF files can be edited")
            val b = bounds[index]
            val scale = (b.x1 - b.x0) / imageWidth
            edit(index, "Recognize text", group) { page ->
                // Rendered pixels -> page space (y down) -> the page's own coordinates.
                val toUser = page.transform.invert()
                val text = OcrText.operators(words) { x, y ->
                    val p = Point(b.x0 + x * scale, b.y0 + y * scale).transform(toUser)
                    p.x to p.y
                }
                val pageObj = page.`object`
                val resources = pageObj.getInheritable("Resources").let { found ->
                    if (found.isNull) pdf.newDictionary().also { pageObj.put("Resources", it) } else found
                }
                val fonts = resources.get("Font").let { found ->
                    if (found.isNull) pdf.newDictionary().also { resources.put("Font", it) } else found
                }
                fonts.put(OcrText.FONT_KEY, OcrText.font(pdf, context))
                // Wrapped in q/Q so whatever state the page's own content leaves can't move it.
                val contents = pageObj.get("Contents")
                val all = pdf.newArray()
                all.push(pdf.addStream("q\n"))
                if (contents.size() > 0) for (i in 0 until contents.size()) all.push(contents.get(i))
                else if (!contents.isNull) all.push(contents)
                all.push(pdf.addStream("Q\n" + text))
                pageObj.put("Contents", all)
            }
        }

    // --- forms -----------------------------------------------------------------------------

    /** The form fields on page [index] (none for pages, or documents, without a form). */
    suspend fun formFields(index: Int): List<FormField> = onEngine {
        val page = doc.loadPage(index) as? PDFPage ?: return@onEngine emptyList()
        try {
            val origin = bounds[index]
            page.widgets.orEmpty().map { w ->
                val kind = when {
                    w.isText -> FieldKind.TEXT
                    w.isCheckbox -> FieldKind.CHECKBOX
                    w.isRadioButton -> FieldKind.RADIO
                    w.isChoice -> FieldKind.CHOICE
                    w.fieldType == com.artifex.mupdf.fitz.PDFWidget.TYPE_SIGNATURE -> FieldKind.SIGNATURE
                    w.isPushButton -> FieldKind.BUTTON
                    else -> FieldKind.OTHER
                }
                val b = w.bounds
                val state = runCatching { w.`object`.get("AS").asName() }.getOrNull()
                FormField(
                    id = w.`object`.asIndirect(),
                    kind = kind,
                    name = runCatching { w.name }.getOrNull().orEmpty(),
                    bounds = PdfRect(b.x0 - origin.x0, b.y0 - origin.y0, b.x1 - origin.x0, b.y1 - origin.y0),
                    value = runCatching { w.value }.getOrNull().orEmpty(),
                    checked = state != null && state != "Off" && state.isNotEmpty(),
                    options = if (kind == FieldKind.CHOICE) w.options?.toList().orEmpty() else emptyList(),
                    multiline = kind == FieldKind.TEXT && w.isMultiline,
                    readOnly = w.isReadOnly,
                )
            }
        } finally {
            page.destroy()
        }
    }

    suspend fun setFieldText(index: Int, id: Int, text: String): EditState = onEngine {
        edit(index, "Fill") { page -> widget(page, id)?.let { it.setTextValue(text); it.update() } }
    }

    /** Ticks or unticks a checkbox, or selects a radio button. */
    suspend fun toggleField(index: Int, id: Int): EditState = onEngine {
        edit(index, "Fill") { page -> widget(page, id)?.let { it.toggle(); it.update() } }
    }

    suspend fun setFieldChoice(index: Int, id: Int, value: String): EditState = onEngine {
        edit(index, "Fill") { page -> widget(page, id)?.let { it.setChoiceValue(value); it.update() } }
    }

    private fun widget(page: PDFPage, id: Int) = page.widgets.orEmpty().firstOrNull { it.`object`.asIndirect() == id }

    /** Whether the file is encrypted (needs a password to open). */
    suspend fun isEncrypted(): Boolean = onEngine {
        val pdf = doc as? PDFDocument ?: return@onEngine false
        !pdf.trailer.get("Encrypt").isNull
    }

    /**
     * Like [saveTo], but sets the password needed to open the file: AES-256 encryption with
     * [newPassword], or none when it's null. The file is rewritten in full. Afterwards this
     * instance must be closed and the file reopened (with the new password).
     */
    suspend fun saveWithPassword(context: Context, uri: Uri, newPassword: String?) = onEngine {
        val pdf = doc as? PDFDocument ?: throw UnsupportedOperationException("Only PDF files can be saved")
        require(newPassword == null || (newPassword.isNotEmpty() && ',' !in newPassword)) {
            "Passwords can't be empty or contain commas"
        }
        val dir = File(context.cacheDir, "saving").apply { mkdirs() }
        val file = File.createTempFile("save", ".pdf", dir)
        try {
            val options = if (newPassword == null) {
                "decrypt,garbage=compact,compress"
            } else {
                "encrypt=aes-256,user-password=$newPassword,owner-password=$newPassword,garbage=compact,compress"
            }
            pdf.save(file.path, options)
            verifySaved(file, bounds.size, newPassword)
            DocumentFiles.replaceBlocking(context, uri, file)
        } finally {
            file.delete()
        }
    }

    /** Whether there are edits not yet written to the file. */
    suspend fun hasUnsavedChanges(): Boolean = onEngine { (doc as? PDFDocument)?.hasUnsavedChanges() == true }

    /**
     * Writes the document with its unsaved edits to [file], a private draft that survives the
     * app being closed (see [DocumentFiles.fingerprint] for telling whether it still fits the
     * original). Removes [file] when there's nothing unsaved. The document itself is unchanged.
     */
    suspend fun saveDraft(file: File) = onEngine {
        val pdf = doc as? PDFDocument
        if (pdf == null || !pdf.hasUnsavedChanges()) {
            file.delete()
            return@onEngine
        }
        file.parentFile?.mkdirs()
        val partial = File(file.parentFile, file.name + ".part")
        pdf.save(partial.path, if (pdf.canBeSavedIncrementally()) "incremental" else "garbage=compact,compress")
        verifySaved(partial, bounds.size)
        if (!partial.renameTo(file)) throw java.io.IOException("Couldn't store the draft")
    }

    /** Undoes the last edit (an eraser gesture counts as one). */
    suspend fun undo(): EditState = onEngine {
        val pdf = doc as? PDFDocument ?: return@onEngine EditState()
        if (journal && pdf.canUndo()) {
            val label = pdf.undoRedoStep(pdf.undoRedoPosition() - 1)
            do pdf.undo() while (pdf.canUndo() && pdf.undoRedoStep(pdf.undoRedoPosition() - 1) == label)
            invalidateAll()
        }
        currentEditState()
    }

    suspend fun redo(): EditState = onEngine {
        val pdf = doc as? PDFDocument ?: return@onEngine EditState()
        if (journal && pdf.canRedo()) {
            val label = pdf.undoRedoStep(pdf.undoRedoPosition())
            do pdf.redo() while (pdf.canRedo() && pdf.undoRedoStep(pdf.undoRedoPosition()) == label)
            invalidateAll()
        }
        currentEditState()
    }

    /**
     * Writes the document, edits included, over [uri] (via a checked temporary file and a
     * backup, see [DocumentFiles.replace]). Changes are appended to the original when the file
     * allows it, which is quick and keeps existing digital signatures valid. Afterwards this
     * instance must be closed and the file reopened: its source has been replaced.
     */
    suspend fun saveTo(context: Context, uri: Uri) = onEngine {
        val pdf = doc as? PDFDocument ?: throw UnsupportedOperationException("Only PDF files can be saved")
        val dir = File(context.cacheDir, "saving").apply { mkdirs() }
        val file = File.createTempFile("save", ".pdf", dir)
        try {
            val appended = pdf.canBeSavedIncrementally() && runCatching {
                pdf.save(file.path, "incremental")
                verifySaved(file, bounds.size)
            }.isSuccess
            if (!appended) {
                pdf.save(file.path, "garbage=compact,compress")
                verifySaved(file, bounds.size)
            }
            DocumentFiles.replaceBlocking(context, uri, file)
        } finally {
            file.delete()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Queued behind any work already submitted, so nothing is freed while in use.
        PdfEngine.executor.execute {
            displayLists.evictAll()
            textPages.evictAll()
            doc.destroy()
            source.close()
        }
    }

    // --- engine-thread helpers -------------------------------------------------------------

    private var journal = false
    private var operations = 0

    /** Extracted text of recently used pages, for selection. */
    private val textPages = object : LruCache<Int, StructuredText>(TEXT_CACHE_SIZE) {
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: StructuredText, newValue: StructuredText?) {
            oldValue.destroy()
        }
    }

    private fun text(index: Int): StructuredText =
        textPages.get(index) ?: run {
            val page = doc.loadPage(index)
            try {
                page.toStructuredText().also { textPages.put(index, it) }
            } finally {
                page.destroy()
            }
        }

    private fun select(index: Int, start: PdfPoint, end: PdfPoint): TextSelection? {
        val st = text(index)
        val a = toPage(index, start)
        val b = toPage(index, end)
        val quads = st.highlight(a, b).orEmpty()
        if (quads.isEmpty()) return null
        val origin = bounds[index]
        val rects = quads.map { q ->
            val r = q.toRect()
            PdfRect(r.x0 - origin.x0, r.y0 - origin.y0, r.x1 - origin.x0, r.y1 - origin.y0)
        }
        return TextSelection(index, start, end, rects, st.copy(a, b).orEmpty())
    }

    private fun toPage(index: Int, p: PdfPoint): Point = bounds[index].let { Point(p.x + it.x0, p.y + it.y0) }

    private fun fromPage(index: Int, x: Float, y: Float): PdfPoint = bounds[index].let { PdfPoint(x - it.x0, y - it.y0) }

    /** Fonts (and their embedded copies in this document) used by text boxes, by name. */
    private val boxFonts = HashMap<String, Pair<Font, PDFObject>>()

    private fun boxFont(pdf: PDFDocument, style: TextBoxStyle): Pair<Font, PDFObject> {
        val name = baseFontName(style)
        return boxFonts.getOrPut(name) {
            val font = Font(name)
            // Embedded as a Unicode font, so accented letters (ő, ű...) work and the text can be searched.
            font to pdf.addFont(font)
        }
    }

    /**
     * Makes [annot] a text box showing [text] in [style], with its top-left corner at [topLeft]
     * (page space). Its look is drawn here (MuPDF's own can't do bold, italic, underline or
     * fills), and the style is kept in the annotation so it can be edited later.
     */
    private fun writeTextBox(pdf: PDFDocument, index: Int, annot: PDFAnnotation, topLeft: Point, text: String, style: TextBoxStyle) {
        val (font, fontRef) = boxFont(pdf, style)
        val lines = text.lines()
        fun widthOf(line: String) = line.codePoints().toArray().sumOf { cp ->
            font.advanceGlyph(font.encodeCharacter(cp), false).toDouble()
        }.toFloat() * style.size
        val border = if (style.border) style.borderWidth else 0f
        val pad = 4f + border
        val lineHeight = style.size * 1.2f
        val page = bounds[index]
        val width = (lines.maxOf(::widthOf) + pad * 2).coerceIn(style.size * 2, (page.x1 - topLeft.x).coerceAtLeast(style.size * 2))
        val height = lines.size * lineHeight + pad * 2

        // Basic, standard description too, for other PDF readers that redraw text boxes themselves.
        annot.setRect(Rect(topLeft.x, topLeft.y, topLeft.x + width, topLeft.y + height))
        annot.setContents(text)
        annot.setDefaultAppearance(
            when (style.font) { TextFont.SANS -> "Helv"; TextFont.SERIF -> "TiRo"; TextFont.MONO -> "Cour" },
            style.size,
            rgb(style.color),
        )
        annot.setBorderWidth(border)
        annot.update()

        // The appearance: background, border, then the text, in the box's own coordinates (y up).
        fun c(color: Int) = rgb(color).joinToString(" ") { "%.3f".format(java.util.Locale.ROOT, it) }
        fun n(v: Float) = "%.2f".format(java.util.Locale.ROOT, v)
        val ops = StringBuilder("q\n")
        if (style.background) ops.append("${c(style.backgroundColor)} rg 0 0 ${n(width)} ${n(height)} re f\n")
        if (style.border && border > 0f) {
            ops.append("${c(style.borderColor)} RG ${n(border)} w ${n(border / 2)} ${n(border / 2)} ${n(width - border)} ${n(height - border)} re S\n")
        }
        ops.append("BT /F1 ${n(style.size)} Tf ${c(style.color)} rg\n")
        lines.forEachIndexed { k, line ->
            val baseline = height - pad - style.size * 0.9f - k * lineHeight
            val glyphs = line.codePoints().toArray().joinToString("") { "%04x".format(font.encodeCharacter(it)) }
            ops.append("1 0 0 1 ${n(pad)} ${n(baseline)} Tm <$glyphs> Tj\n")
        }
        ops.append("ET\n")
        if (style.underline) {
            ops.append("${c(style.color)} RG ${n(style.size / 16f)} w\n")
            lines.forEachIndexed { k, line ->
                if (line.isBlank()) return@forEachIndexed
                val y = height - pad - style.size * 0.9f - k * lineHeight - style.size * 0.12f
                ops.append("${n(pad)} ${n(y)} m ${n(pad + widthOf(line))} ${n(y)} l S\n")
            }
        }
        ops.append("Q\n")

        val resources = pdf.newDictionary()
        resources.put("Font", pdf.newDictionary().also { it.put("F1", fontRef) })
        val form = pdf.newDictionary()
        form.put("Type", pdf.newName("XObject"))
        form.put("Subtype", pdf.newName("Form"))
        form.put("BBox", pdf.newArray().also { a -> listOf(0f, 0f, width, height).forEach { a.push(it) } })
        form.put("Resources", resources)
        val ap = pdf.newDictionary()
        ap.put("N", pdf.addStream(ops.toString(), form))
        val obj = annot.`object`
        obj.put("AP", ap)
        // Background colour, as PDF readers expect it for text boxes.
        if (style.background) obj.put("C", pdf.newArray().also { a -> rgb(style.backgroundColor).forEach { a.push(it) } })
        else obj.delete("C")

        val kept = pdf.newDictionary()
        kept.put("Font", style.font.ordinal)
        kept.put("Size", style.size)
        kept.put("Bold", style.bold)
        kept.put("Italic", style.italic)
        kept.put("Underline", style.underline)
        kept.put("Color", style.color)
        kept.put("Border", style.border)
        kept.put("BorderWidth", style.borderWidth)
        kept.put("BorderColor", style.borderColor)
        kept.put("Background", style.background)
        kept.put("BackgroundColor", style.backgroundColor)
        obj.put(STYLE_KEY, kept)
    }

    private fun readTextBoxStyle(annot: PDFAnnotation): TextBoxStyle {
        val kept = annot.`object`.get(STYLE_KEY)
        val d = TextBoxStyle()
        if (kept.isNull) {
            val size = runCatching { annot.defaultAppearance?.size }.getOrNull()?.takeIf { it > 0f } ?: d.size
            return d.copy(size = size)
        }
        fun int(key: String, default: Int) = kept.get(key).let { if (it.isNull) default else it.asInteger() }
        fun float(key: String, default: Float) = kept.get(key).let { if (it.isNull) default else it.asFloat() }
        fun bool(key: String, default: Boolean) = kept.get(key).let { if (it.isNull) default else it.asBoolean() }
        return TextBoxStyle(
            font = TextFont.entries.getOrElse(int("Font", 0)) { TextFont.SANS },
            size = float("Size", d.size),
            bold = bool("Bold", false),
            italic = bool("Italic", false),
            underline = bool("Underline", false),
            color = int("Color", d.color),
            border = bool("Border", false),
            borderWidth = float("BorderWidth", d.borderWidth),
            borderColor = int("BorderColor", d.borderColor),
            background = bool("Background", false),
            backgroundColor = int("BackgroundColor", d.backgroundColor),
        )
    }

    /** Runs one undoable change to page [index] and refreshes what's cached for it. */
    private fun edit(index: Int, label: String, group: String? = null, block: (PDFPage) -> Unit): EditState {
        val pdf = doc as? PDFDocument ?: throw UnsupportedOperationException("Only PDF files can be annotated")
        if (!journal) {
            pdf.enableJournal()
            journal = true
        }
        val page = doc.loadPage(index) as PDFPage
        pdf.beginOperation(group ?: "$label#${++operations}")
        try {
            block(page)
            page.update()
            pdf.endOperation()
        } catch (e: Exception) {
            pdf.abandonOperation()
            throw e
        } finally {
            page.destroy()
            displayLists.remove(index)
            textPages.remove(index)
        }
        return currentEditState()
    }

    /** Like [edit], for changes that aren't on a page. */
    private fun editDocument(label: String, block: () -> Unit): EditState {
        val pdf = doc as? PDFDocument ?: throw UnsupportedOperationException("Only PDF files can be edited")
        if (!journal) {
            pdf.enableJournal()
            journal = true
        }
        pdf.beginOperation("$label#${++operations}")
        try {
            block()
            pdf.endOperation()
        } catch (e: Exception) {
            pdf.abandonOperation()
            throw e
        }
        return currentEditState()
    }

    private fun currentEditState(): EditState {
        val pdf = doc as? PDFDocument
        if (pdf == null || !journal) return EditState()
        return EditState(canUndo = pdf.canUndo(), canRedo = pdf.canRedo())
    }

    private fun invalidateAll() {
        displayLists.evictAll()
        textPages.evictAll()
    }

    private fun find(page: PDFPage, id: Int): PDFAnnotation? =
        page.annotations.orEmpty().firstOrNull { it.`object`.asIndirect() == id }

    private fun PDFAnnotation.info(index: Int): AnnotationInfo {
        val kind = when (type) {
            PDFAnnotation.TYPE_TEXT -> AnnotationKind.NOTE
            PDFAnnotation.TYPE_HIGHLIGHT -> AnnotationKind.HIGHLIGHT
            PDFAnnotation.TYPE_UNDERLINE -> AnnotationKind.UNDERLINE
            PDFAnnotation.TYPE_STRIKE_OUT -> AnnotationKind.STRIKEOUT
            PDFAnnotation.TYPE_INK -> AnnotationKind.INK
            PDFAnnotation.TYPE_FREE_TEXT -> AnnotationKind.TEXT_BOX
            PDFAnnotation.TYPE_SQUARE, PDFAnnotation.TYPE_CIRCLE, PDFAnnotation.TYPE_LINE -> AnnotationKind.SHAPE
            else -> AnnotationKind.OTHER
        }
        val b = bounds
        val o = this@PdfDocument.bounds[index]
        return AnnotationInfo(
            id = `object`.asIndirect(),
            kind = kind,
            bounds = PdfRect(b.x0 - o.x0, b.y0 - o.y0, b.x1 - o.x0, b.y1 - o.y0),
            contents = runCatching { contents }.getOrNull().orEmpty(),
        )
    }

    /**
     * Copies the annotations of [src]'s page [index] onto [target], a page just grafted from it
     * (grafting a page leaves annotations behind). Links and form fields are skipped: links can
     * point into the other document, and fields belong to its form.
     */
    private fun copyAnnotations(src: PDFDocument, index: Int, target: PDFObject) {
        val pdf = doc as PDFDocument
        // MuPDF's Java resolve() wrappers can release objects they don't own, which loses
        // them from the saved file; get(), put() and delete() follow references by themselves.
        val annots = src.findPage(index).get("Annots")
        if (annots.isNull || !annots.isArray || annots.size() == 0) return
        val keep = (0 until annots.size()).map { annots.get(it) }.filter {
            val type = it.get("Subtype").asName()
            type != "Link" && type != "Widget"
        }
        if (keep.isEmpty()) return
        // Each annotation points back to its page (/P), and from there to the whole page tree;
        // cut those links first so only the annotations themselves are copied.
        keep.forEach { it.delete("P") }
        // A map of its own, so a page copied twice gets two sets of annotations, not one shared.
        val map = pdf.newPDFGraftMap()
        try {
            val copies = pdf.newArray()
            for (annot in keep) {
                val copy = map.graftObject(annot)
                copy.put("P", target)
                copies.push(copy)
            }
            target.put("Annots", copies)
        } finally {
            map.destroy()
        }
    }

    /** Reopens a written file to make sure it's readable and has every page. */
    private fun verifySaved(file: File, expectedPages: Int, password: String? = this.password) {
        val check = Document.openDocument(file.path)
        try {
            val readable = !check.needsPassword() || password?.let(check::authenticatePassword) == true
            if (!readable || check.countPages() != expectedPages) {
                throw java.io.IOException("The saved file didn't come out right")
            }
        } finally {
            check.destroy()
        }
    }

    private suspend fun <T> onEngine(block: () -> T): T = withContext(PdfEngine.dispatcher) {
        check(!closed) { "Document is closed" }
        block()
    }

    private fun loadLayout() {
        bounds = List(doc.countPages()) { i ->
            val page = doc.loadPage(i)
            try {
                Rect(page.bounds)
            } finally {
                page.destroy()
            }
        }
        isLocked = false
    }

    private fun displayList(index: Int): DisplayList =
        displayLists.get(index) ?: run {
            val page = doc.loadPage(index)
            try {
                page.toDisplayList().also { displayLists.put(index, it) }
            } finally {
                page.destroy()
            }
        }

    private fun draw(index: Int, fullWidth: Int, fullHeight: Int, x: Int, y: Int, width: Int, height: Int): Bitmap {
        val b = bounds[index]
        val sx = fullWidth / (b.x1 - b.x0)
        val sy = fullHeight / (b.y1 - b.y0)
        val ctm = Matrix(sx, 0f, 0f, sy, -b.x0 * sx, -b.y0 * sy)
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val device = AndroidDrawDevice(bitmap, x, y)
        try {
            displayList(index).run(device, ctm, null)
            device.close()
        } finally {
            device.destroy()
        }
        return bitmap
    }

    private fun resolve(link: Link): LinkTarget? {
        if (link.isExternal) return LinkTarget.External(link.uri)
        val page = runCatching { doc.pageNumberFromLocation(doc.resolveLink(link)) }.getOrDefault(-1)
        return if (page in bounds.indices) LinkTarget.Page(page) else null
    }

    companion object {
        private const val DISPLAY_LIST_CACHE_SIZE = 12
        private const val TEXT_CACHE_SIZE = 4
        private const val NOTE_SIZE = 24f
        private const val NOTE_COLOR = 0xFFD54F

        private fun rgb(color: Int) = floatArrayOf(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f,
        )
        private const val COPY_DIR = "opened"

        /**
         * Opens a document from any content or file URI. Reads directly from the file when the
         * provider offers a seekable file; otherwise (e.g. some cloud providers) copies it to the
         * app's cache first. Check [isLocked] afterwards and call [authenticate] if needed.
         */
        suspend fun open(context: Context, uri: Uri): PdfDocument = withContext(PdfEngine.dispatcher) {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: "application/pdf"
            val stream = openDirect(resolver, uri) ?: openViaCopy(context, resolver, uri)
            val document = try {
                Document.openDocument(stream, mimeType)
            } catch (e: Exception) {
                stream.close()
                throw e
            }
            PdfDocument(document, stream).apply {
                if (!document.needsPassword()) loadLayout()
            }
        }

        /**
         * Writes a copy of the PDF [input] with its annotations and form fields drawn into the
         * pages themselves, for printing: some printing paths (Android's own PDF renderer among
         * them) leave annotations out. The copy is never encrypted, for the same reason.
         */
        suspend fun flatten(input: File, output: File, password: String? = null) = withContext(PdfEngine.dispatcher) {
            val doc = Document.openDocument(input.path) as? PDFDocument
                ?: throw UnsupportedOperationException("Only PDF files can be flattened")
            try {
                if (doc.needsPassword() && (password == null || !doc.authenticatePassword(password))) {
                    throw SecurityException("Wrong password")
                }
                doc.bake(true, true)
                output.parentFile?.mkdirs()
                // Unencrypted: printing goes through renderers that can't open protected files.
                doc.save(output.path, "decrypt,garbage,compress")
            } finally {
                doc.destroy()
            }
        }

        /**
         * Writes a smaller copy of the PDF [input] to [output]: large JPEG images are scaled down
         * so their longest side is at most [maxImageSide] pixels and re-encoded at [quality];
         * fonts are cut down to the characters used; duplicate objects are merged and
         * everything is compressed. An image is only replaced when that makes it smaller, and
         * images with transparency or masks are left as they are.
         */
        suspend fun compress(
            input: File,
            output: File,
            password: String? = null,
            maxImageSide: Int = 2000,
            quality: Int = 75,
        ) = withContext(PdfEngine.dispatcher) {
            val doc = Document.openDocument(input.path) as? PDFDocument
                ?: throw UnsupportedOperationException("Only PDF files can be compressed")
            try {
                if (doc.needsPassword() && (password == null || !doc.authenticatePassword(password))) {
                    throw SecurityException("Wrong password")
                }
                run {
                    // Images are found through each page's resources (and those of drawings
                    // nested in them), never by walking raw object numbers, which can upset
                    // MuPDF's view of some files.
                    val seen = HashSet<Int>()
                    fun visit(resources: PDFObject) {
                        // get() follows references itself; resolve() wrappers are avoided here.
                        val xObjects = resources.get("XObject")
                        if (!xObjects.isDictionary) return
                        for (key in xObjects) {
                            val ref = xObjects.get(key)
                            if (ref.isIndirect && !seen.add(ref.asIndirect())) continue
                            when (ref.get("Subtype").asName()) {
                                "Image" -> runCatching { shrinkImage(doc, ref, maxImageSide, quality) }
                                "Form" -> visit(ref.get("Resources"))
                            }
                        }
                    }
                    for (i in 0 until doc.countPages()) runCatching { visit(doc.findPage(i).getInheritable("Resources")) }
                }
                runCatching { doc.subsetFonts() }
                output.parentFile?.mkdirs()
                doc.save(output.path, "garbage=deduplicate,compress")
            } finally {
                doc.destroy()
            }
        }

        private fun shrinkImage(doc: PDFDocument, ref: PDFObject, maxSide: Int, quality: Int) {
            val obj = ref
            if (!obj.isStream || obj.get("Subtype").asName() != "Image") return
            val filter = obj.get("Filter").let { if (it.isArray && it.size() == 1) it.get(0) else it }
            if (filter.asName() != "DCTDecode") return
            if (!obj.get("SMask").isNull || !obj.get("Mask").isNull || obj.get("ImageMask").asBoolean()) return
            // Grey or colour only (CMYK and others are left alone). Asked of the decoded image:
            // the colour space entry may be a reference, which can't be inspected safely here.
            val components = doc.loadImage(ref).let { image ->
                try {
                    image.numberOfComponents
                } finally {
                    image.destroy()
                }
            }
            if (components != 1 && components != 3) return
            val width = obj.get("Width").asInteger()
            val height = obj.get("Height").asInteger()
            if (maxOf(width, height) <= maxSide) return

            val original = obj.readRawStream()
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
            val decoded = android.graphics.BitmapFactory.decodeByteArray(
                original, 0, original.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return
            val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true,
                ).also { if (it !== decoded) decoded.recycle() }
            } else decoded
            val jpeg = java.io.ByteArrayOutputStream().use {
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it)
                it.toByteArray()
            }
            val (newWidth, newHeight) = scaled.width to scaled.height
            scaled.recycle()
            if (jpeg.size >= original.size) return
            // Android writes colour (YCbCr) JPEGs, so the image becomes RGB.
            obj.writeRawStream(com.artifex.mupdf.fitz.Buffer().apply { writeBytes(jpeg) })
            obj.put("Width", newWidth)
            obj.put("Height", newHeight)
            obj.put("ColorSpace", doc.newName("DeviceRGB"))
            obj.put("BitsPerComponent", 8)
            obj.delete("DecodeParms")
            obj.delete("Decode")
        }

        private fun openDirect(resolver: ContentResolver, uri: Uri): FdSeekableInputStream? {
            val pfd = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
            if (pfd.statSize < 0) { // a pipe or socket: can't seek
                pfd.close()
                return null
            }
            return FdSeekableInputStream(pfd)
        }

        private fun openViaCopy(context: Context, resolver: ContentResolver, uri: Uri): FdSeekableInputStream {
            val dir = File(context.cacheDir, COPY_DIR).apply { mkdirs() }
            val file = File.createTempFile("doc", ".tmp", dir)
            try {
                val input = resolver.openInputStream(uri) ?: error("Can't read $uri")
                input.use { inp -> file.outputStream().use { inp.copyTo(it) } }
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                return FdSeekableInputStream(pfd) { file.delete() }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        }
    }
}

/** [a b c d e f] mirroring about x = sumX / 2 and/or y = sumY / 2. */
private fun mirror(h: Boolean, v: Boolean, sumX: Float, sumY: Float) =
    floatArrayOf(if (h) -1f else 1f, 0f, 0f, if (v) -1f else 1f, if (h) sumX else 0f, if (v) sumY else 0f)

/** First [m], then [n] (PDF's row-vector order). */
private fun concat(m: FloatArray, n: FloatArray) = floatArrayOf(
    m[0] * n[0] + m[1] * n[2], m[0] * n[1] + m[1] * n[3],
    m[2] * n[0] + m[3] * n[2], m[2] * n[1] + m[3] * n[3],
    m[4] * n[0] + m[5] * n[2] + n[4], m[4] * n[1] + m[5] * n[3] + n[5],
)

private fun floats(array: PDFObject) = FloatArray(array.size()) { array.get(it).asFloat() }

private fun transformPoint(x: Float, y: Float, m: FloatArray) = (x * m[0] + y * m[2] + m[4]) to (x * m[1] + y * m[3] + m[5])

/** The box around [box] (x0 y0 x1 y1) once transformed by [m]. */
private fun transformBox(box: FloatArray, m: FloatArray): FloatArray {
    val corners = listOf(box[0] to box[1], box[2] to box[1], box[0] to box[3], box[2] to box[3]).map { transformPoint(it.first, it.second, m) }
    return floatArrayOf(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
}

private fun boxArray(pdf: PDFDocument, box: FloatArray) = pdf.newArray().also { a -> box.forEach { a.push(it) } }

private fun pointArray(pdf: PDFDocument, xy: FloatArray, m: FloatArray) = pdf.newArray().also { a ->
    for (i in 0 until xy.size / 2) {
        val (x, y) = transformPoint(xy[2 * i], xy[2 * i + 1], m)
        a.push(x)
        a.push(y)
    }
}

private fun fmt(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else "%.4f".format(java.util.Locale.ROOT, f)

private const val INFO_SUBJECT = "info:Subject"
private const val INFO_KEYWORDS = "info:Keywords"

/** Where a text box's style is kept, for editing it later. */
private const val STYLE_KEY = "SzabadPDFStyle"

/** The standard PDF font for a text box style, e.g. "Times-BoldItalic". */
private fun baseFontName(style: TextBoxStyle): String {
    val family = when (style.font) {
        TextFont.SANS -> "Helvetica"
        TextFont.SERIF -> "Times"
        TextFont.MONO -> "Courier"
    }
    val slant = if (style.font == TextFont.SERIF) "Italic" else "Oblique"
    return when {
        style.bold && style.italic -> "$family-Bold$slant"
        style.bold -> "$family-Bold"
        style.italic -> "$family-$slant"
        style.font == TextFont.SERIF -> "Times-Roman"
        else -> family
    }
}
