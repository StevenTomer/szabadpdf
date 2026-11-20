package org.szabadpdf.core.scan

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

/** Runs against testdata/scan-photo.ps: a page turned 8° on a dark desk (assets/photo.jpg). */
@RunWith(AndroidJUnit4::class)
class DocumentScannerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scanner = DocumentScanner(instrumentation.targetContext)
    private val photo = instrumentation.context.assets.open("photo.jpg").use(BitmapFactory::decodeStream)

    /** Where the page's corners really are, worked out from the PostScript. */
    private val expected = Corners(
        PointF(0.1561f, 0.2365f), PointF(0.7337f, 0.1756f), PointF(0.8439f, 0.7635f), PointF(0.2663f, 0.8244f),
    )

    @Test
    fun findsThePage() = runBlocking {
        val analysis = scanner.analyze(photo)
        val corners = analysis.corners
        assertNotNull("no document found", corners)
        corners!!.points.zip(expected.points).forEachIndexed { i, (found, want) ->
            assertTrue("corner $i at $found, expected $want", hypot(found.x - want.x, found.y - want.y) < 0.03f)
        }
    }

    @Test
    fun extractsAFlatUprightPage() = runBlocking {
        val page = scanner.extract(photo, expected, 0, ScanColor.GRAYSCALE, ScanColor.GRAYSCALE)
        assertEquals("page proportions", 950f / 700f, page.height.toFloat() / page.width, 0.05f)
        // Mostly paper, with dark text in the top half and none at the bottom.
        val pixels = IntArray(page.width * page.height).also { page.getPixels(it, 0, page.width, 0, 0, page.width, page.height) }
        val light = pixels.count { Color.red(it) > 200 }
        assertTrue("mostly white", light > pixels.size * 0.85)
        fun darkIn(top: Float, bottom: Float) = (0 until page.width).sumOf { x ->
            ((page.height * top).toInt() until (page.height * bottom).toInt()).count { y -> Color.red(page.getPixel(x, y)) < 100 }
        }
        assertTrue("text near the top", darkIn(0.05f, 0.5f) > 1000)
        assertEquals("nothing near the bottom", 0, darkIn(0.85f, 0.95f))
    }

    @Test
    fun rotatesWhenAsked() = runBlocking {
        val page = scanner.extract(photo, expected, 90, ScanColor.COLOR, ScanColor.COLOR)
        assertTrue("landscape after a quarter turn", page.width > page.height)
    }

    @Test
    fun cornersRotateWithTheImage() {
        val c = Corners.WHOLE_IMAGE.rotated(1)
        assertEquals(PointF(0f, 0f), c.topLeft)
        val p = Corners(PointF(0.1f, 0.2f), PointF(0.9f, 0.2f), PointF(0.9f, 0.8f), PointF(0.1f, 0.8f)).rotated(1)
        // Turning clockwise, the old bottom-left becomes the top-left.
        assertEquals(0.2f, p.topLeft.x, 1e-6f)
        assertEquals(0.1f, p.topLeft.y, 1e-6f)
    }

    @Test
    fun readsTheTextOnAnExtractedPage() = runBlocking {
        val page = scanner.extract(photo, expected, 0, ScanColor.GRAYSCALE, ScanColor.GRAYSCALE)
        val recognizer = TextRecognizer(instrumentation.targetContext)
        try {
            val words = recognizer.recognize(page)
            val text = words.joinToString(" ") { it.text }
            assertTrue("read: $text", text.contains("Meeting notes"))
            assertTrue("read: $text", text.contains("quick brown fox jumps over the lazy dog"))
            // Boxes are within the page and in reading order down the page.
            assertTrue(words.all { it.left >= 0 && it.right <= page.width && it.top >= 0 && it.bottom <= page.height })
            assertTrue(words.first().text == "Meeting")
        } finally {
            recognizer.close()
        }
    }
}
