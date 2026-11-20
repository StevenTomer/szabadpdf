package org.szabadpdf.feature.organize

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.szabadpdf.core.pdf.DocumentFiles
import org.szabadpdf.core.pdf.PageSize
import org.szabadpdf.core.pdf.PageSpec
import org.szabadpdf.core.pdf.PdfDocument
import java.io.File

/**
 * One page in the edited document. [source] says where it comes from: [MAIN] (the document
 * being organized), [BLANK], or an inserted document (1, 2, ...). [size] is the page as it
 * currently looks, before the extra [rotation] (clockwise degrees, kept un-wrapped so turning
 * animates the short way).
 */
data class EditPage(
    val id: Long,
    val source: Int,
    val index: Int,
    val size: PageSize,
    val rotation: Int = 0,
) {
    val displaySize: PageSize
        get() = if (Math.floorMod(rotation, 180) == 90) PageSize(size.height, size.width) else size

    companion object {
        const val MAIN = 0
        const val BLANK = -1
    }
}

sealed interface OrganizeStatus {
    data object Loading : OrganizeStatus
    data object Ready : OrganizeStatus
    data object Saving : OrganizeStatus
    data class Error(val message: String) : OrganizeStatus
}

data class OrganizeState(
    val status: OrganizeStatus = OrganizeStatus.Loading,
    val name: String = "",
    val pages: List<EditPage> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** False when the app that gave us the file only lets us read it; then only "save as copy" works. */
    val canOverwrite: Boolean = false,
) {
    val dirty: Boolean get() = canUndo
}

sealed interface OrganizeEvent {
    data class Saved(val uri: Uri) : OrganizeEvent
    data class Message(val text: String) : OrganizeEvent
}

/** Page editing for one document: the edit list, undo history, thumbnails and saving. */
class OrganizeViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(OrganizeState())
    val state: StateFlow<OrganizeState> = _state.asStateFlow()

    private val _events = Channel<OrganizeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var uri: Uri? = null
    private var password: String? = null
    private var main: PdfDocument? = null
    private val inserted = mutableListOf<PdfDocument>()
    private var nextId = 0L
    private var openJob: Job? = null

    private val undoStack = ArrayDeque<List<EditPage>>()
    private val redoStack = ArrayDeque<List<EditPage>>()

    /** Pages as they were when a drag began; committed to history when it ends. */
    private var dragStart: List<EditPage>? = null

    private val thumbnails = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Starts organizing [uri] unless it already is. [password] is for encrypted documents. */
    fun start(uri: Uri, name: String, password: String?) {
        if (this.uri == uri) return
        discard()
        this.uri = uri
        this.password = password
        _state.value = OrganizeState(name = name)
        openJob = viewModelScope.launch {
            try {
                val doc = openDocument(uri, password)
                if (!doc.isEditable) {
                    doc.close()
                    throw UnsupportedOperationException("Only PDF files can be organized")
                }
                main = doc
                _state.value = OrganizeState(
                    status = OrganizeStatus.Ready,
                    name = name,
                    pages = doc.pageSizes.mapIndexed { i, size -> EditPage(nextId++, EditPage.MAIN, i, size) },
                    canOverwrite = DocumentFiles.canWrite(getApplication(), uri),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = OrganizeStatus.Error(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /** Drops the document and every unsaved change. */
    fun discard() {
        openJob?.cancel()
        main?.close()
        main = null
        inserted.forEach(PdfDocument::close)
        inserted.clear()
        uri = null
        password = null
        undoStack.clear()
        redoStack.clear()
        dragStart = null
        thumbnails.evictAll()
        _state.value = OrganizeState()
    }

    override fun onCleared() = discard()

    // --- selection -------------------------------------------------------------------------

    fun toggleSelected(id: Long) = _state.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAll() = _state.update { s -> s.copy(selected = s.pages.mapTo(HashSet()) { it.id }) }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    // --- edits -----------------------------------------------------------------------------

    fun rotateSelected(degrees: Int) = edit { s ->
        s.pages.map { if (it.id in s.selected) it.copy(rotation = it.rotation + degrees) else it }
    }

    fun deleteSelected() {
        val s = _state.value
        if (s.pages.all { it.id in s.selected }) {
            _events.trySend(OrganizeEvent.Message("A document needs at least one page"))
            return
        }
        edit { it.pages.filterNot { p -> p.id in it.selected } }
        clearSelection()
    }

    /** Moves the selected pages, in order, to the start or end. */
    fun moveSelected(toStart: Boolean) = edit { s ->
        val (picked, rest) = s.pages.partition { it.id in s.selected }
        if (toStart) picked + rest else rest + picked
    }

    fun insertBlank() = edit { s ->
        val at = insertionPoint(s)
        val like = s.pages.getOrNull(at - 1) ?: s.pages.firstOrNull()
        val size = like?.displaySize ?: A4
        s.pages.toMutableList().apply { add(at, EditPage(nextId++, EditPage.BLANK, 0, size)) }
    }

    /** Inserts every page of another PDF after the selection (or at the end). */
    fun insertDocument(from: Uri) {
        viewModelScope.launch {
            val doc = try {
                PdfDocument.open(getApplication(), from)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(OrganizeEvent.Message("Couldn't open that file"))
                return@launch
            }
            if (doc.isLocked || !doc.isEditable || doc.pageCount == 0) {
                doc.close()
                _events.send(
                    OrganizeEvent.Message(
                        if (doc.isLocked) "Password-protected PDFs can't be inserted yet" else "That file has no pages to insert",
                    ),
                )
                return@launch
            }
            if (main == null) { // discarded meanwhile
                doc.close()
                return@launch
            }
            inserted += doc
            val source = inserted.size
            val count = doc.pageCount
            edit { s ->
                val at = insertionPoint(s)
                val added = doc.pageSizes.mapIndexed { i, size -> EditPage(nextId++, source, i, size) }
                s.pages.toMutableList().apply { addAll(at, added) }
            }
            _events.send(OrganizeEvent.Message("Inserted $count page${if (count == 1) "" else "s"}"))
        }
    }

    fun beginDrag() {
        dragStart = _state.value.pages
    }

    /** Live move while dragging; recorded as one undo step by [endDrag]. */
    fun move(from: Int, to: Int) = _state.update { s ->
        if (from !in s.pages.indices || to !in s.pages.indices) s
        else s.copy(pages = s.pages.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun endDrag() {
        val before = dragStart ?: return
        dragStart = null
        if (before != _state.value.pages) record(before)
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.pages)
        setPages(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.pages)
        setPages(next)
    }

    private fun edit(change: (OrganizeState) -> List<EditPage>) {
        val s = _state.value
        if (s.status != OrganizeStatus.Ready) return
        val pages = change(s)
        if (pages == s.pages) return
        record(s.pages)
        setPages(pages)
    }

    private fun record(before: List<EditPage>) {
        undoStack.addLast(before)
        if (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        _state.update { it.copy(canUndo = true, canRedo = false) }
    }

    private fun setPages(pages: List<EditPage>) = _state.update { s ->
        val ids = pages.mapTo(HashSet()) { it.id }
        s.copy(
            pages = pages,
            selected = s.selected.filterTo(HashSet()) { it in ids },
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
        )
    }

    private fun insertionPoint(s: OrganizeState): Int {
        val last = s.pages.indexOfLast { it.id in s.selected }
        return if (last >= 0) last + 1 else s.pages.size
    }

    // --- thumbnails ------------------------------------------------------------------------

    fun cachedThumbnail(page: EditPage, width: Int): Bitmap? = thumbnails.get(key(page, width))

    suspend fun thumbnail(page: EditPage, width: Int): Bitmap? {
        if (page.source == EditPage.BLANK) return null
        cachedThumbnail(page, width)?.let { return it }
        val doc = documentFor(page.source) ?: return null
        return runCatching { doc.renderPage(page.index, width) }
            .onSuccess { thumbnails.put(key(page, width), it) }
            .getOrNull()
    }

    private fun key(page: EditPage, width: Int) = "${page.source}:${page.index}:$width"

    private fun documentFor(source: Int): PdfDocument? =
        if (source == EditPage.MAIN) main else inserted.getOrNull(source - 1)

    // --- saving ----------------------------------------------------------------------------

    /** Saves over the original (when [target] is null) or to [target], a new file. */
    fun save(target: Uri? = null) {
        val original = uri ?: return
        val s = _state.value
        if (s.status != OrganizeStatus.Ready) return
        val destination = target ?: original
        _state.update { it.copy(status = OrganizeStatus.Saving) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val dir = File(app.cacheDir, "saving").apply { mkdirs() }
            val output = File(dir, "out-${System.nanoTime()}.pdf")
            // A fresh copy is rebuilt and written out, so the one showing thumbnails stays
            // untouched if anything goes wrong.
            var fresh: PdfDocument? = null
            try {
                val doc = openDocument(original, password).also { fresh = it }
                val specs = s.pages.map { page ->
                    when (page.source) {
                        EditPage.BLANK -> PageSpec.Blank(page.size, page.rotation)
                        EditPage.MAIN -> PageSpec.Existing(page.index, rotation = page.rotation)
                        else -> PageSpec.Existing(page.index, from = documentFor(page.source), rotation = page.rotation)
                    }
                }
                doc.saveRearranged(specs, output)
                doc.close()
                fresh = null
                DocumentFiles.replace(app, destination, output)
                _events.send(OrganizeEvent.Saved(destination))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = OrganizeStatus.Ready) }
                _events.send(OrganizeEvent.Message("Couldn't save: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                fresh?.close()
                output.delete()
            }
        }
    }

    private suspend fun openDocument(uri: Uri, password: String?): PdfDocument {
        val doc = PdfDocument.open(getApplication(), uri)
        if (doc.isLocked && (password == null || !doc.authenticate(password))) {
            doc.close()
            throw SecurityException("This document is password-protected; open it in the viewer first")
        }
        return doc
    }

    private companion object {
        const val HISTORY_LIMIT = 100
        val A4 = PageSize(595f, 842f)
    }
}
