package org.szabadpdf.feature.scan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.szabadpdf.core.pdf.DocumentFiles
import org.szabadpdf.core.pdf.ImagePdf
import org.szabadpdf.core.scan.Corners
import org.szabadpdf.core.scan.DocumentScanner
import org.szabadpdf.core.scan.QuadStabilizer
import org.szabadpdf.core.scan.ScanColor
import java.io.File
import kotlin.math.max

/**
 * One scanned page. [source] is the upright photo, kept so the crop, rotation and colour can
 * be changed later; [result] is the finished page image. [version] changes with [result].
 */
data class ScanPage(
    val id: Long,
    val source: File,
    val result: File,
    val corners: Corners,
    val detected: Boolean,
    val rotation: Int,
    val color: ScanColor,
    val suggestedColor: ScanColor,
    val version: Int = 0,
)

data class ScanState(
    val pages: List<ScanPage> = emptyList(),
    /** Photos still being turned into pages. */
    val processing: Int = 0,
    val saving: Boolean = false,
)

/** The live outline, in fractions of the upright camera frame of [frameWidth] × [frameHeight]. */
data class LiveOutline(val corners: Corners?, val frameWidth: Int, val frameHeight: Int)

sealed interface ScanEvent {
    data class Saved(val uri: Uri) : ScanEvent
    data class Message(val text: String) : ScanEvent
}

/** A scanning session: camera detection, captured pages, edits and saving to PDF. */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = DocumentScanner(application)
    private val stabilizer = QuadStabilizer()
    private val sessionLock = Mutex()

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _live = MutableStateFlow<LiveOutline?>(null)
    val live: StateFlow<LiveOutline?> = _live.asStateFlow()

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var session: File? = null
    private var nextId = 0L

    init {
        viewModelScope.launch { runCatching { scanner.warmUp() } }
    }

    private fun sessionDir(): File = session ?: File(getApplication<Application>().cacheDir, "scan/${System.currentTimeMillis()}")
        .apply { mkdirs() }
        .also { session = it }

    // --- camera ----------------------------------------------------------------------------

    /** Detection on a preview frame. Closes [frame] when done, which lets the next one in. */
    fun liveFrame(frame: ImageProxy) {
        val rotation = frame.imageInfo.rotationDegrees
        val upright = if (rotation % 180 == 0) frame.width to frame.height else frame.height to frame.width
        val bitmap = try {
            frame.toBitmap()
        } catch (e: Exception) {
            frame.close()
            return
        }
        viewModelScope.launch {
            try {
                val raw = runCatching { scanner.detectLive(bitmap, rotation) }.getOrNull()
                _live.value = LiveOutline(stabilizer.update(raw), upright.first, upright.second)
            } finally {
                bitmap.recycle()
                frame.close()
            }
        }
    }

    fun resetLive() {
        stabilizer.reset()
        _live.value = null
    }

    /** A photo from the camera, as delivered with its [rotationDegrees]. */
    fun capture(photo: Bitmap, rotationDegrees: Int) = addPhoto(imported = false) { upright(photo, rotationDegrees) }

    fun import(uris: List<Uri>) {
        for (uri in uris) addPhoto(imported = true) { decode(uri) }
    }

    private fun addPhoto(imported: Boolean, load: suspend () -> Bitmap?) {
        _state.update { it.copy(processing = it.processing + 1) }
        viewModelScope.launch {
            try {
                val photo = withContext(Dispatchers.IO) { load() } ?: throw IllegalArgumentException("Couldn't read the image")
                val id = nextId++
                val dir = sessionDir()
                val source = File(dir, "source-$id.jpg")
                withContext(Dispatchers.IO) { source.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                val analysis = scanner.analyze(photo, imported)
                val draft = ScanPage(
                    id = id,
                    source = source,
                    result = File(dir, "page-$id-0.jpg"),
                    corners = analysis.corners ?: Corners.WHOLE_IMAGE,
                    detected = analysis.corners != null,
                    rotation = 0,
                    color = analysis.suggestedColor,
                    suggestedColor = analysis.suggestedColor,
                )
                val page = render(draft, photo)
                photo.recycle()
                sessionLock.withLock { _state.update { it.copy(pages = it.pages + page) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ScanEvent.Message("Couldn't process that photo: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                _state.update { it.copy(processing = it.processing - 1) }
            }
        }
    }

    // --- editing ---------------------------------------------------------------------------

    fun setCorners(id: Long, corners: Corners) = edit(id) { it.copy(corners = corners) }

    fun rotate(id: Long) = edit(id) { it.copy(rotation = it.rotation + 90) }

    fun setColor(id: Long, color: ScanColor) = edit(id) { it.copy(color = color) }

    fun delete(id: Long) = _state.update { s ->
        s.pages.firstOrNull { it.id == id }?.let { it.result.delete(); it.source.delete() }
        s.copy(pages = s.pages.filterNot { it.id == id })
    }

    fun move(id: Long, by: Int) = _state.update { s ->
        val from = s.pages.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in s.pages.indices) s
        else s.copy(pages = s.pages.toMutableList().apply { add(to, removeAt(from)) })
    }

    private fun edit(id: Long, change: (ScanPage) -> ScanPage) {
        val page = _state.value.pages.firstOrNull { it.id == id } ?: return
        val draft = change(page)
        viewModelScope.launch {
            try {
                val photo = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(page.source.path) }
                    ?: throw IllegalStateException("The photo is gone")
                val next = draft.copy(
                    version = page.version + 1,
                    result = File(page.result.parentFile, "page-$id-${page.version + 1}.jpg"),
                )
                val rendered = render(next, photo)
                photo.recycle()
                sessionLock.withLock {
                    _state.update { s -> s.copy(pages = s.pages.map { if (it.id == id) rendered else it }) }
                }
                page.result.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ScanEvent.Message("Couldn't update the page: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    /** Produces [page]'s finished image from [photo]. Black-and-white pages are kept as PNG. */
    private suspend fun render(page: ScanPage, photo: Bitmap): ScanPage {
        val image = scanner.extract(photo, page.corners, page.rotation, page.color, page.suggestedColor)
        val bw = page.color == ScanColor.BLACK_AND_WHITE
        val file = File(page.result.parentFile, page.result.nameWithoutExtension + if (bw) ".png" else ".jpg")
        withContext(Dispatchers.IO) {
            file.outputStream().use {
                image.compress(if (bw) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 80, it)
            }
        }
        image.recycle()
        return page.copy(result = file)
    }

    // --- saving ----------------------------------------------------------------------------

    fun save(target: Uri, title: String) {
        val pages = _state.value.pages
        if (pages.isEmpty() || _state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val out = File(sessionDir(), "scan.pdf")
            try {
                ImagePdf.write(pages.map { it.result }, out, title)
                DocumentFiles.replace(getApplication(), target, out)
                _events.send(ScanEvent.Saved(target))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(ScanEvent.Message("Couldn't save: ${e.message ?: e.javaClass.simpleName}"))
            } finally {
                out.delete()
                _state.update { it.copy(saving = false) }
            }
        }
    }

    /** Ends the session and deletes its photos. */
    fun discard() {
        session?.deleteRecursively()
        session = null
        resetLive()
        _state.value = ScanState()
    }

    override fun onCleared() {
        discard()
        scanner.close()
    }

    // --- images ----------------------------------------------------------------------------

    private suspend fun upright(photo: Bitmap, rotationDegrees: Int): Bitmap = withContext(Dispatchers.Default) {
        if (rotationDegrees % 360 == 0) return@withContext photo
        val turned = Bitmap.createBitmap(
            photo, 0, 0, photo.width, photo.height, Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
        )
        if (turned !== photo) photo.recycle()
        turned
    }

    /** Decodes an imported image, scaled down to at most [MAX_SIDE] and turned upright per its EXIF. */
    private fun decode(uri: Uri): Bitmap? {
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val degrees = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        if (degrees == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    }

    private fun exifRotation(input: java.io.InputStream): Int = runCatching {
        when (android.media.ExifInterface(input).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private companion object {
        const val MAX_SIDE = 4000
    }
}
