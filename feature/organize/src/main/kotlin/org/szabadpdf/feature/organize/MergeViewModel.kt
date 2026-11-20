package org.szabadpdf.feature.organize

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.szabadpdf.core.pdf.DocumentFiles
import org.szabadpdf.core.pdf.PageSpec
import org.szabadpdf.core.pdf.PdfDocument
import java.io.File

/** One file to merge. [document] is open for reading its pages. */
class MergeFile(val id: Long, val uri: Uri, val name: String, val document: PdfDocument) {
    val pageCount: Int get() = document.pageCount
}

data class MergeState(
    val files: List<MergeFile> = emptyList(),
    /** Files still being opened. */
    val adding: Int = 0,
    val saving: Boolean = false,
) {
    val pageCount: Int get() = files.sumOf { it.pageCount }
}

sealed interface MergeEvent {
    data class Saved(val uri: Uri) : MergeEvent
    data class Message(val text: String) : MergeEvent
}

/** Combines several PDFs, in the order chosen, into one new file. The originals aren't changed. */
class MergeViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(MergeState())
    val state: StateFlow<MergeState> = _state.asStateFlow()

    private val _events = Channel<MergeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var nextId = 0L

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(adding = it.adding + uris.size) }
        viewModelScope.launch {
            for (uri in uris) {
                val file = try {
                    val doc = PdfDocument.open(getApplication(), uri)
                    when {
                        doc.isLocked -> {
                            doc.close()
                            _events.send(MergeEvent.Message("${nameOf(uri)} is password-protected and can't be merged yet"))
                            null
                        }
                        !doc.isEditable || doc.pageCount == 0 -> {
                            doc.close()
                            _events.send(MergeEvent.Message("${nameOf(uri)} has no pages to merge"))
                            null
                        }
                        else -> MergeFile(nextId++, uri, nameOf(uri), doc)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.send(MergeEvent.Message("Couldn't open ${nameOf(uri)}"))
                    null
                }
                _state.update { s -> s.copy(files = if (file != null) s.files + file else s.files, adding = s.adding - 1) }
            }
        }
    }

    fun move(id: Long, by: Int) = _state.update { s ->
        val from = s.files.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in s.files.indices) s
        else s.copy(files = s.files.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun remove(id: Long) = _state.update { s ->
        s.files.firstOrNull { it.id == id }?.document?.close()
        s.copy(files = s.files.filterNot { it.id == id })
    }

    /** Writes the merged document to [target], a new file. */
    fun save(target: Uri) {
        val files = _state.value.files
        if (files.size < 2 || _state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val output = File(File(app.cacheDir, "saving").apply { mkdirs() }, "merge-${System.nanoTime()}.pdf")
            // The first file is rebuilt (in a fresh copy) with everyone's pages, so its title,
            // bookmarks and other settings carry over.
            var base: PdfDocument? = null
            try {
                base = PdfDocument.open(app, files.first().uri)
                val specs = files.flatMapIndexed { i, file ->
                    (0 until file.pageCount).map { page -> PageSpec.Existing(page, from = if (i == 0) null else file.document) }
                }
                base.saveRearranged(specs, output)
                base.close()
                base = null
                DocumentFiles.replace(app, target, output)
                _events.send(MergeEvent.Saved(target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(MergeEvent.Message("Couldn't merge: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                base?.close()
                output.delete()
                _state.update { it.copy(saving = false) }
            }
        }
    }

    /** Closes everything and starts over. */
    fun clear() {
        _state.value.files.forEach { it.document.close() }
        _state.value = MergeState()
    }

    override fun onCleared() = clear()

    private fun nameOf(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "Document"
}
