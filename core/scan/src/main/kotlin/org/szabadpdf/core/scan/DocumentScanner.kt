package org.szabadpdf.core.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fairscan.imageprocessing.ColorMode
import org.fairscan.imageprocessing.ImageSize
import org.fairscan.imageprocessing.Mode
import org.fairscan.imageprocessing.Point
import org.fairscan.imageprocessing.Quad
import org.fairscan.imageprocessing.autoColorMode
import org.fairscan.imageprocessing.detectDocumentQuad
import org.fairscan.imageprocessing.extractDocument
import org.fairscan.imageprocessing.scaledTo
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/** A document's corners in an image, as fractions of its width and height. */
data class Corners(val topLeft: PointF, val topRight: PointF, val bottomRight: PointF, val bottomLeft: PointF) {
    val points: List<PointF> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun maxDistanceTo(other: Corners): Float =
        points.zip(other.points).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) }

    /** The same corners after turning the image clockwise by [quarterTurns] × 90°. */
    fun rotated(quarterTurns: Int): Corners {
        var c = this
        repeat(Math.floorMod(quarterTurns, 4)) {
            fun turn(p: PointF) = PointF(1f - p.y, p.x)
            c = Corners(turn(c.bottomLeft), turn(c.topLeft), turn(c.topRight), turn(c.bottomRight))
        }
        return c
    }

    companion object {
        val WHOLE_IMAGE = Corners(PointF(0f, 0f), PointF(1f, 0f), PointF(1f, 1f), PointF(0f, 1f))
    }
}

enum class ScanColor { COLOR, GRAYSCALE, BLACK_AND_WHITE }

/** What was found in a photo: the document's outline (if any) and which colour mode suits it. */
data class Analysis(val corners: Corners?, val suggestedColor: ScanColor)

/**
 * Finds documents in photos and turns them into clean, flat page images, using FairScan's
 * segmentation model and image processing. Photos passed in must already be upright.
 */
class DocumentScanner(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var segmentation: Segmentation? = null

    private fun model(): Segmentation = segmentation ?: run {
        check(OpenCVLoader.initLocal()) { "OpenCV failed to load" }
        Segmentation(appContext).also { segmentation = it }
    }

    /** Loads OpenCV and the model ahead of first use. */
    suspend fun warmUp() {
        withContext(Dispatchers.Default) { lock.withLock { model() } }
    }

    /**
     * Quick detection for the live camera view. [frame] is as the camera delivered it; the
     * corners returned are for the frame turned upright by [rotationDegrees].
     */
    suspend fun detectLive(frame: Bitmap, rotationDegrees: Int): Corners? = withContext(Dispatchers.Default) {
        val mask = lock.withLock { model().run(frame) }
        val quad = detectDocumentQuad(mask, ImageSize(frame.width, frame.height), Mode.LIVE_ANALYSIS)
            ?: return@withContext null
        quad.toCorners(mask.width, mask.height).rotated(rotationDegrees / 90)
    }

    /** Careful detection on a full photo (taken or imported). */
    suspend fun analyze(photo: Bitmap, imported: Boolean = false): Analysis = withContext(Dispatchers.Default) {
        val mask = lock.withLock { model().run(photo) }
        val quadInMask = detectDocumentQuad(
            mask, ImageSize(photo.width, photo.height), if (imported) Mode.IMPORT else Mode.CAPTURE,
        ) ?: return@withContext Analysis(null, ScanColor.COLOR)
        val quad = quadInMask.scaledTo(mask.width, mask.height, photo.width, photo.height)
        val bgr = photo.toBgr()
        try {
            Analysis(quadInMask.toCorners(mask.width, mask.height), autoColorMode(bgr, mask, quad).toScanColor())
        } finally {
            bgr.release()
        }
    }

    /**
     * Cuts the document out of [photo] along [corners], flattens the perspective, cleans it
     * up for [color] and turns it clockwise by [rotationDegrees].
     */
    suspend fun extract(
        photo: Bitmap,
        corners: Corners,
        rotationDegrees: Int,
        color: ScanColor,
        suggestedColor: ScanColor,
        maxPixels: Long = DEFAULT_MAX_PIXELS,
    ): Bitmap = withContext(Dispatchers.Default) {
        model() // makes sure OpenCV is loaded
        val bgr = photo.toBgr()
        var page: Mat? = null
        try {
            val quad = corners.toQuad(photo.width, photo.height)
            val pixels = if (color == ScanColor.BLACK_AND_WHITE) maxPixels * 4 else maxPixels
            page = extractDocument(
                bgr, quad, Math.floorMod(rotationDegrees, 360), color.toColorMode(), suggestedColor.toColorMode(), pixels,
            )
            page.toBitmap()
        } finally {
            bgr.release()
            page?.release()
        }
    }

    fun close() {
        segmentation?.close()
        segmentation = null
    }

    companion object {
        /** About 2 megapixels: sharp text at a modest file size (FairScan's "balanced"). */
        const val DEFAULT_MAX_PIXELS = 2_000_000L
    }
}

private fun Quad.toCorners(width: Int, height: Int): Corners {
    fun n(p: Point) = PointF((p.x / width).toFloat().coerceIn(0f, 1f), (p.y / height).toFloat().coerceIn(0f, 1f))
    return Corners(n(topLeft), n(topRight), n(bottomRight), n(bottomLeft))
}

private fun Corners.toQuad(width: Int, height: Int): Quad {
    fun p(f: PointF) = Point(f.x.toDouble() * width, f.y.toDouble() * height)
    return Quad(p(topLeft), p(topRight), p(bottomRight), p(bottomLeft))
}

private fun Bitmap.toBgr(): Mat {
    val rgba = Mat()
    Utils.bitmapToMat(this, rgba)
    val bgr = Mat()
    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
    rgba.release()
    return bgr
}

private fun Mat.toBitmap(): Bitmap {
    val rgba = Mat()
    Imgproc.cvtColor(this, rgba, if (channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_BGR2RGBA)
    val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(rgba, bitmap)
    rgba.release()
    return bitmap
}

private fun ColorMode.toScanColor() = when (this) {
    ColorMode.COLOR -> ScanColor.COLOR
    ColorMode.GRAYSCALE -> ScanColor.GRAYSCALE
    ColorMode.BLACK_AND_WHITE -> ScanColor.BLACK_AND_WHITE
}

private fun ScanColor.toColorMode() = when (this) {
    ScanColor.COLOR -> ColorMode.COLOR
    ScanColor.GRAYSCALE -> ColorMode.GRAYSCALE
    ScanColor.BLACK_AND_WHITE -> ColorMode.BLACK_AND_WHITE
}
