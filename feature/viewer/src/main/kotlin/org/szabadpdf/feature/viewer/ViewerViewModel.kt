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
import org.szabadpdf.core.pdf.LinkTarget
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

/** What the page list needs from whoever owns the document. */
interface PageSource {
    fun cached(page: Int, width: Int): Bitmap?
    suspend fun render(page: Int, width: Int): Bitmap?
    suspend fun renderRegion(page: Int, fullWidth: Int, fullHeight: Int, x: Int, y: Int, width: Int, height: Int): Bitmap?
}

/** Holds the open document across configuration changes. One document at a time. */
class ViewerViewModel(application: Application) : AndroidViewModel(application), PageSource {

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Idle)
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private var document: PdfDocument? = null
    private var uri: Uri? = null
    private var fallbackTitle: String = ""
    private var initialPage = 0
    private var outline: List<OutlineEntry>? = null
    private var openJob: Job? = null
    private var searchJob: Job? = null

    /** Whole-page bitmaps, bounded by bytes: an eighth of the app's heap. */
    private val pageCache = object : LruCache<Long, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    /** Opens [uri] unless it's already the open document. */
    fun open(uri: Uri, displayName: String, initialPage: Int) {
        if (this.uri == uri) return
        close()
        this.uri = uri
        this.fallbackTitle = displayName
        this.initialPage = initialPage
        _state.value = ViewerState.Loading
        openJob = viewModelScope.launch {
            try {
                val doc = PdfDocument.open(getApplication(), uri)
                document = doc
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
            if (doc.authenticate(password)) showDocument(doc)
            else _state.value = ViewerState.NeedsPassword(wrongPassword = true)
        }
    }

    fun close() {
        openJob?.cancel()
        searchJob?.cancel()
        document?.close()
        document = null
        uri = null
        outline = null
        pageCache.evictAll()
        _search.value = SearchState()
        _state.value = ViewerState.Idle
    }

    override fun onCleared() = close()

    private suspend fun showDocument(doc: PdfDocument) {
        val title = doc.title() ?: fallbackTitle
        _state.value = ViewerState.Ready(
            DocumentInfo(
                uri = uri ?: return,
                title = title,
                pageSizes = doc.pageSizes,
                initialPage = initialPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0)),
            ),
        )
    }

    // --- rendering -------------------------------------------------------------------------

    override fun cached(page: Int, width: Int): Bitmap? = pageCache.get(key(page, width))

    override suspend fun render(page: Int, width: Int): Bitmap? {
        cached(page, width)?.let { return it }
        val doc = document ?: return null
        return runCatching { doc.renderPage(page, width) }
            .onSuccess { pageCache.put(key(page, width), it) }
            .getOrNull()
    }

    override suspend fun renderRegion(
        page: Int, fullWidth: Int, fullHeight: Int, x: Int, y: Int, width: Int, height: Int,
    ): Bitmap? {
        val doc = document ?: return null
        return runCatching { doc.renderRegion(page, fullWidth, fullHeight, x, y, width, height) }.getOrNull()
    }

    private fun key(page: Int, width: Int) = (page.toLong() shl 32) or width.toLong()

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
}
