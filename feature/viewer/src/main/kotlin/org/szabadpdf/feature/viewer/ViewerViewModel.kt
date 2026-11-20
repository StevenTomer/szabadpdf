package org.szabadpdf.feature.viewer

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.szabadpdf.core.pdf.AnnotationInfo
import org.szabadpdf.core.pdf.DocumentFiles
import org.szabadpdf.core.pdf.EditState
import org.szabadpdf.core.pdf.InkStyle
import org.szabadpdf.core.pdf.LinkTarget
import org.szabadpdf.core.pdf.MarkupKind
import org.szabadpdf.core.pdf.PdfPoint
import org.szabadpdf.core.pdf.TextSelection
import org.szabadpdf.core.pdf.OutlineEntry
import org.szabadpdf.core.pdf.PageSize
import org.szabadpdf.core.pdf.PdfDocument
import org.szabadpdf.core.pdf.PdfRect

sealed interface ViewerState {
    data object Idle : ViewerState
    data object Loading : ViewerState
    data class NeedsPassword(val wrongPassword: Boolean) : ViewerState
    data class Ready(val document: DocumentInfo) : ViewerState
    data class Error(val message: String) : ViewerState
}

data class DocumentInfo(
    val uri: Uri,
    /** The file's name, e.g. for naming a copy. */
    val name: String,
    val title: String,
    val pageSizes: List<PageSize>,
    val initialPage: Int,
)

/** One search match: a page and the rectangles (in points) covering the matched text. */
data class SearchHit(val page: Int, val rects: List<PdfRect>)

data class SearchState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val current: Int = -1,
    val running: Boolean = false,
) {
    val currentHit: SearchHit? get() = hits.getOrNull(current)
}

/** What the page list needs from whoever owns the document. [version] changes when a page is edited. */
interface PageSource {
    fun cached(page: Int, width: Int, version: Long): Bitmap?
    suspend fun render(page: Int, width: Int, version: Long): Bitmap?
    suspend fun renderRegion(page: Int, fullWidth: Int, fullHeight: Int, x: Int, y: Int, width: Int, height: Int): Bitmap?
}

/** Bumped when pages change, so their pictures are redrawn: [all] for every page (undo), else per page. */
data class PageVersions(val all: Int = 0, val pages: Map<Int, Int> = emptyMap()) {
    fun of(page: Int): Long = (all.toLong() shl 32) or (pages[page] ?: 0).toLong()
}

enum class Tool(val draws: Boolean) {
    /** Tap annotations to edit or delete them. */
    SELECT(false),
    PEN(true),
    HIGHLIGHTER(true),
    /** Drag across text to highlight it. */
    TEXT_HIGHLIGHT(true),
    NOTE(false),
    ERASER(true),
}

data class AnnotateState(
    /** Annotating (tools showing) rather than just reading. */
    val active: Boolean = false,
    val tool: Tool = Tool.PEN,
    val pen: InkStyle = InkStyle(0x1A1A1A, 1.5f),
    val highlighter: InkStyle = InkStyle(0xFFEB3B, 12f, 0.4f),
    val markupColor: Int = 0xFFEB3B,
    /** When off, only a stylus draws and fingers scroll. Turned off the first time a stylus is used. */
    val fingerDraws: Boolean = true,
    val edits: EditState = EditState(),
    val saving: Boolean = false,
    val canOverwrite: Boolean = false,
    val editable: Boolean = false,
) {
    val dirty: Boolean get() = edits.canUndo
    val currentTool: Tool? get() = if (active) tool else null
    val inkStyle: InkStyle? get() = when (currentTool) {
        Tool.PEN -> pen
        Tool.HIGHLIGHTER -> highlighter
        else -> null
    }
}

/** A stroke that's been drawn and is being added to the document. */
data class PendingStroke(val points: List<PdfPoint>, val style: InkStyle)

sealed interface ViewerEvent {
    data class Message(val text: String) : ViewerEvent
    data object Saved : ViewerEvent
    data class SavedAs(val uri: Uri) : ViewerEvent
}

/** Holds the open document across configuration changes. One document at a time. */
class ViewerViewModel(application: Application) : AndroidViewModel(application), PageSource {

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Idle)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val prefs = application.getSharedPreferences("viewer", android.content.Context.MODE_PRIVATE)

    private val _annotate = MutableStateFlow(loadAnnotatePrefs())
    val annotate: StateFlow<AnnotateState> = _annotate.asStateFlow()

    private val _versions = MutableStateFlow(PageVersions())
    val versions: StateFlow<PageVersions> = _versions.asStateFlow()

    private val _pending = MutableStateFlow<Map<Int, List<PendingStroke>>>(emptyMap())
    val pendingInk: StateFlow<Map<Int, List<PendingStroke>>> = _pending.asStateFlow()

    private val _selection = MutableStateFlow<TextSelection?>(null)
    val selection: StateFlow<TextSelection?> = _selection.asStateFlow()

    private val _events = Channel<ViewerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The width each page was last drawn at, so edited pages can be redrawn before showing. */
    private val lastWidth = HashMap<Int, Int>()
    private var selectJob: Job? = null
    private var displayName: String = ""

    private var document: PdfDocument? = null

    /** The password that unlocked the open document, if it needed one. */
    var password: String? = null
        private set
    private var uri: Uri? = null
    private var fallbackTitle: String = ""
    private var initialPage = 0
    private var outline: List<OutlineEntry>? = null
    private var openJob: Job? = null
    private var searchJob: Job? = null

    /** Whole-page bitmaps, bounded by bytes: an eighth of the app's heap. */
    private val pageCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Opens [uri] unless it's already the open document (or [reload] is set, e.g. after saving). */
    fun open(uri: Uri, displayName: String, initialPage: Int, reload: Boolean = false) {
        if (this.uri == uri && !reload) return
        val knownPassword = password.takeIf { this.uri == uri }
        close()
        this.uri = uri
        this.fallbackTitle = displayName
        this.displayName = displayName
        this.initialPage = initialPage
        _state.value = ViewerState.Loading
        openJob = viewModelScope.launch {
            try {
                val doc = PdfDocument.open(getApplication(), uri)
                document = doc
                if (doc.isLocked && knownPassword != null && doc.authenticate(knownPassword)) password = knownPassword
                if (doc.isLocked) _state.value = ViewerState.NeedsPassword(wrongPassword = false)
                else showDocument(doc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ViewerState.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun submitPassword(password: String) {
        val doc = document ?: return
        viewModelScope.launch {
            if (doc.authenticate(password)) {
                this@ViewerViewModel.password = password
                showDocument(doc)
            } else _state.value = ViewerState.NeedsPassword(wrongPassword = true)
        }
    }

    fun close() {
        openJob?.cancel()
        searchJob?.cancel()
        document?.close()
        document = null
        password = null
        uri = null
        outline = null
        pageCache.evictAll()
        lastWidth.clear()
        selectJob?.cancel()
        _search.value = SearchState()
        _selection.value = null
        _pending.value = emptyMap()
        _versions.value = PageVersions()
        _annotate.update { it.copy(active = false, edits = EditState(), saving = false) }
        _state.value = ViewerState.Idle
    }

    override fun onCleared() = close()

    private suspend fun showDocument(doc: PdfDocument) {
        val current = uri ?: return
        _annotate.update {
            it.copy(editable = doc.isEditable, canOverwrite = DocumentFiles.canWrite(getApplication(), current))
        }
        val title = doc.title() ?: fallbackTitle
        _state.value = ViewerState.Ready(
            DocumentInfo(
                uri = current,
                name = displayName,
                title = title,
                pageSizes = doc.pageSizes,
                initialPage = initialPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0)),
            ),
        )
    }

    // --- rendering -------------------------------------------------------------------------

    override fun cached(page: Int, width: Int, version: Long): Bitmap? = pageCache.get(key(page, width, version))

    override suspend fun render(page: Int, width: Int, version: Long): Bitmap? {
        lastWidth[page] = width
        cached(page, width, version)?.let { return it }
        val doc = document ?: return null
        return runCatching { doc.renderPage(page, width) }
            .onSuccess { pageCache.put(key(page, width, version), it) }
            .getOrNull()
    }

    override suspend fun renderRegion(
        page: Int, fullWidth: Int, fullHeight: Int, x: Int, y: Int, width: Int, height: Int,
    ): Bitmap? {
        val doc = document ?: return null
        return runCatching { doc.renderRegion(page, fullWidth, fullHeight, x, y, width, height) }.getOrNull()
    }

    private fun key(page: Int, width: Int, version: Long) = "$page:$width:$version"

    // --- navigation helpers ----------------------------------------------------------------

    suspend fun linkAt(page: Int, x: Float, y: Float): LinkTarget? =
        document?.let { runCatching { it.linkAt(page, x, y) }.getOrNull() }

    suspend fun outline(): List<OutlineEntry> {
        outline?.let { return it }
        val doc = document ?: return emptyList()
        return runCatching { doc.outline() }.getOrDefault(emptyList()).also { outline = it }
    }

    // --- search ----------------------------------------------------------------------------

    /** Searches every page, starting at [fromPage] and wrapping around; hits arrive as found. */
    fun search(query: String, fromPage: Int) {
        searchJob?.cancel()
        val doc = document
        if (query.isBlank() || doc == null) {
            _search.value = SearchState(query = query)
            return
        }
        _search.value = SearchState(query = query, running = true)
        searchJob = viewModelScope.launch {
            val count = doc.pageCount
            val found = mutableListOf<SearchHit>()
            for (step in 0 until count) {
                val page = (fromPage + step) % count
                val hits = runCatching { doc.search(page, query) }.getOrNull() ?: break
                if (hits.isEmpty()) continue
                found += hits.map { SearchHit(page, it) }
                // Keep hits in page order; the first hit shown is the first at or after fromPage.
                val sorted = found.sortedWith(compareBy({ it.page }, { it.rects.first().top }))
                _search.update { s ->
                    val current = s.currentHit?.let(sorted::indexOf)?.takeIf { it >= 0 }
                        ?: sorted.indexOfFirst { it.page >= fromPage }.takeIf { it >= 0 } ?: 0
                    s.copy(hits = sorted, current = current)
                }
            }
            _search.update { it.copy(running = false) }
        }
    }

    fun nextHit() = _search.update {
        if (it.hits.isEmpty()) it else it.copy(current = (it.current + 1) % it.hits.size)
    }

    fun previousHit() = _search.update {
        if (it.hits.isEmpty()) it else it.copy(current = (it.current - 1 + it.hits.size) % it.hits.size)
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchState()
    }

    // --- annotating ------------------------------------------------------------------------

    fun setAnnotating(active: Boolean) {
        _annotate.update { it.copy(active = active && it.editable) }
    }

    fun selectTool(tool: Tool) = _annotate.update { it.copy(tool = tool) }

    fun setPenStyle(style: InkStyle) = updatePrefs { it.copy(pen = style) }

    fun setHighlighterStyle(style: InkStyle) = updatePrefs { it.copy(highlighter = style) }

    fun setMarkupColor(color: Int) = updatePrefs { it.copy(markupColor = color) }

    fun setFingerDraws(enabled: Boolean) = updatePrefs { it.copy(fingerDraws = enabled) }

    /** The first time a stylus is used, let fingers scroll instead of draw. */
    fun onStylusSeen() {
        if (prefs.getBoolean(KEY_STYLUS_SEEN, false)) return
        prefs.edit().putBoolean(KEY_STYLUS_SEEN, true).apply()
        if (_annotate.value.fingerDraws) {
            setFingerDraws(false)
            _events.trySend(ViewerEvent.Message("Pen detected: draw with the pen, scroll with your finger. Change this in the pen settings."))
        }
    }

    fun addStroke(page: Int, points: List<PdfPoint>) {
        val doc = document ?: return
        val style = _annotate.value.inkStyle ?: return
        if (points.isEmpty()) return
        val stroke = PendingStroke(points, style)
        _pending.update { it + (page to (it[page].orEmpty() + stroke)) }
        viewModelScope.launch {
            val result = runCatching { doc.addInk(page, points, style) }
            if (result.isSuccess) pageChanged(page)
            _pending.update { map ->
                val rest = map[page].orEmpty() - stroke
                if (rest.isEmpty()) map - page else map + (page to rest)
            }
            result.onSuccess(::setEdits).onFailure { report("Couldn't add the stroke", it) }
        }
    }

    /** Erases ink along a piece of an eraser gesture; a whole gesture is one undo step. */
    fun erase(page: Int, points: List<PdfPoint>, radius: Float, gesture: Int) {
        val doc = document ?: return
        viewModelScope.launch {
            val result = runCatching { doc.erase(page, points, radius, gesture) }.getOrNull() ?: return@launch
            pageChanged(page)
            setEdits(result)
        }
    }

    suspend fun annotationAt(page: Int, point: PdfPoint, slop: Float): AnnotationInfo? =
        document?.let { runCatching { it.annotationAt(page, point, slop) }.getOrNull() }

    fun addNote(page: Int, point: PdfPoint, text: String) = editPage(page, "Couldn't add the note") {
        it.addNote(page, point, text)
    }

    fun setNoteText(page: Int, id: Int, text: String) = editPage(page, "Couldn't change the note") {
        it.setContents(page, id, text)
    }

    fun deleteAnnotation(page: Int, id: Int) = editPage(page, "Couldn't delete that") {
        it.deleteAnnotation(page, id)
    }

    fun undo() = editAll { it.undo() }

    fun redo() = editAll { it.redo() }

    // --- text selection --------------------------------------------------------------------

    fun selectWord(page: Int, point: PdfPoint) {
        val doc = document ?: return
        selectJob?.cancel()
        selectJob = viewModelScope.launch {
            _selection.value = runCatching { doc.selectWord(page, point) }.getOrNull()
        }
    }

    /** Selects from [start] to [end]; while dragging, only the latest request is worked on. */
    fun selectRange(page: Int, start: PdfPoint, end: PdfPoint) {
        val doc = document ?: return
        selectJob?.cancel()
        selectJob = viewModelScope.launch {
            val selection = runCatching { doc.selectText(page, start, end) }.getOrNull()
            // Keep the endpoints the finger chose even when they're between words.
            _selection.value = selection?.copy(start = start, end = end)
        }
    }

    fun clearSelection() {
        selectJob?.cancel()
        _selection.value = null
    }

    fun markupSelection(kind: MarkupKind) {
        val s = _selection.value ?: return
        val color = when (kind) {
            MarkupKind.HIGHLIGHT -> _annotate.value.markupColor
            MarkupKind.UNDERLINE -> 0x1E88E5
            MarkupKind.STRIKEOUT -> 0xE53935
        }
        clearSelection()
        editPage(s.page, "Couldn't mark the text") { it.addMarkup(s.page, s.start, s.end, kind, color) }
    }

    /** The text-highlight tool: drag across text, highlighted on release. */
    fun highlightDrag(page: Int, start: PdfPoint, end: PdfPoint, finished: Boolean) {
        if (!finished) {
            selectRange(page, start, end)
            return
        }
        val color = _annotate.value.markupColor
        clearSelection()
        editPage(page, "Couldn't highlight the text") { it.addMarkup(page, start, end, MarkupKind.HIGHLIGHT, color) }
    }

    // --- saving ----------------------------------------------------------------------------

    /** Saves over the open file (when [target] is null) or to [target], a new file. */
    fun save(target: Uri? = null) {
        val doc = document ?: return
        val current = uri ?: return
        if (_annotate.value.saving) return
        _annotate.update { it.copy(saving = true) }
        viewModelScope.launch {
            val destination = target ?: current
            try {
                doc.saveTo(getApplication(), destination)
                if (target != null) {
                    // The app switches to the new file; this one is left unchanged.
                    _annotate.update { it.copy(saving = false) }
                    _events.send(ViewerEvent.SavedAs(target))
                    return@launch
                }
                // The file underneath has been replaced; read from the new one from now on.
                val fresh = PdfDocument.open(getApplication(), current)
                password?.let { fresh.authenticate(it) }
                document = fresh
                doc.close()
                outline = null
                _annotate.update { it.copy(saving = false, edits = EditState()) }
                _events.send(ViewerEvent.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _annotate.update { it.copy(saving = false) }
                report("Couldn't save", e)
            }
        }
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun editPage(page: Int, failure: String, change: suspend (PdfDocument) -> EditState) {
        val doc = document ?: return
        viewModelScope.launch {
            runCatching { change(doc) }
                .onSuccess {
                    pageChanged(page)
                    setEdits(it)
                }
                .onFailure { report(failure, it) }
        }
    }

    private fun editAll(change: suspend (PdfDocument) -> EditState) {
        val doc = document ?: return
        viewModelScope.launch {
            runCatching { change(doc) }.onSuccess {
                outline = null
                _versions.update { v -> PageVersions(all = v.all + 1) }
                setEdits(it)
            }
        }
    }

    /** Draws the changed page ahead of time, so it swaps in without flashing blank. */
    private suspend fun pageChanged(page: Int) {
        val v = _versions.value
        val next = v.copy(pages = v.pages + (page to (v.pages[page] ?: 0) + 1))
        lastWidth[page]?.let { width ->
            document?.let { doc ->
                runCatching { doc.renderPage(page, width) }.onSuccess { pageCache.put(key(page, width, next.of(page)), it) }
            }
        }
        _versions.value = next
    }

    private fun setEdits(edits: EditState) = _annotate.update { it.copy(edits = edits) }

    private fun report(what: String, error: Throwable) {
        if (error is CancellationException) return
        _events.trySend(ViewerEvent.Message("$what: ${error.message ?: error.javaClass.simpleName}"))
    }

    private fun updatePrefs(change: (AnnotateState) -> AnnotateState) {
        val s = change(_annotate.value)
        _annotate.value = s
        prefs.edit()
            .putInt("pen_color", s.pen.color).putFloat("pen_width", s.pen.width)
            .putInt("hl_color", s.highlighter.color).putFloat("hl_width", s.highlighter.width)
            .putInt("markup_color", s.markupColor)
            .putBoolean("finger_draws", s.fingerDraws)
            .apply()
    }

    private fun loadAnnotatePrefs(): AnnotateState {
        val d = AnnotateState()
        return d.copy(
            pen = InkStyle(prefs.getInt("pen_color", d.pen.color), prefs.getFloat("pen_width", d.pen.width)),
            highlighter = InkStyle(
                prefs.getInt("hl_color", d.highlighter.color),
                prefs.getFloat("hl_width", d.highlighter.width),
                d.highlighter.opacity,
            ),
            markupColor = prefs.getInt("markup_color", d.markupColor),
            fingerDraws = prefs.getBoolean("finger_draws", d.fingerDraws),
        )
    }

    private companion object {
        const val KEY_STYLUS_SEEN = "stylus_seen"
    }
}
