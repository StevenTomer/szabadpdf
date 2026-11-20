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
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFGraftMap
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
     * The rebuild happens to this document in memory, so it should be a fresh instance opened
     * only for saving and closed afterwards; documents named in [PageSpec.Existing.from] are
     * only read. The written file is reopened and checked before this returns.
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
                            graftMaps.getOrPut(from) { pdf.newPDFGraftMap() }.graftPage(-1, src, spec.index)
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
            require(order.distinct().size == order.size) { "A page can't appear twice" }
            pdf.rearrangePages(order)

            pages.forEachIndexed { i, spec ->
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

        val check = Document.openDocument(target.path)
        try {
            val readable = !check.needsPassword() || password?.let(check::authenticatePassword) == true
            if (!readable || check.countPages() != pages.size) {
                throw java.io.IOException("The saved file didn't come out right")
            }
        } finally {
            check.destroy()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Queued behind any work already submitted, so nothing is freed while in use.
        PdfEngine.executor.execute {
            displayLists.evictAll()
            doc.destroy()
            source.close()
        }
    }

    // --- engine-thread helpers -------------------------------------------------------------

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
