package org.szabadpdf.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImagePdfTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun image(name: String, w: Int, h: Int, format: Bitmap.CompressFormat): File {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (x in 0 until w / 4) for (y in 0 until h / 4) bitmap.setPixel(x, y, Color.BLACK) // top-left block
        return File(context.cacheDir, name).apply { outputStream().use { bitmap.compress(format, 90, it) } }
    }

    @Test
    fun writesOnePagePerImageKeepingProportions() = runBlocking {
        val portrait = image("p.jpg", 1000, 1400, Bitmap.CompressFormat.JPEG)
        val landscape = image("l.png", 1600, 1000, Bitmap.CompressFormat.PNG)
        val out = File(context.cacheDir, "images.pdf").apply { delete() }
        ImagePdf.write(context, listOf(portrait, landscape), out, title = "Scan")

        val doc = PdfDocument.open(context, Uri.fromFile(out))
        try {
            assertEquals(2, doc.pageCount)
            val (p, l) = doc.pageSizes
            assertEquals(1400f / 1000f, p.height / p.width, 0.01f)
            assertTrue("portrait fits A4", p.width <= 595.5f && p.height <= 842.5f)
            assertEquals(1000f / 1600f, l.height / l.width, 0.01f)
            assertEquals("Scan", doc.title())
            val bitmap = doc.renderPage(0, 500)
            // JPEG blurs edges a little, so check well inside the black block and the white area.
            assertTrue("top-left is dark", Color.red(bitmap.getPixel(20, 20)) < 40)
            assertTrue("bottom-right is light", Color.red(bitmap.getPixel(450, 650)) > 215)
        } finally {
            doc.close()
        }
    }

    @Test
    fun ocrTextIsSearchableWhereTheWordsAre() = runBlocking {
        // A 1000 x 1400 image; two words on one line, 40 px high, 100 px from the top.
        val image = image("ocr.jpg", 1000, 1400, Bitmap.CompressFormat.JPEG)
        val words = listOf(
            OcrWord("Hello", 100, 100, 260, 140, 40, 140),
            OcrWord("Wörld", 300, 100, 470, 140, 40, 140),
        )
        val out = File(context.cacheDir, "ocr.pdf").apply { delete() }
        ImagePdf.write(context, listOf(image), out, text = listOf(words))

        val doc = PdfDocument.open(context, Uri.fromFile(out))
        try {
            val k = doc.pageSizes[0].width / 1000f // points per image pixel
            val hits = doc.search(0, "wörld")
            assertEquals(1, hits.size)
            val r = hits[0][0]
            assertEquals(300 * k, r.left, 3f)
            assertEquals(470 * k, r.right, 3f)
            assertTrue("hit sits on the word's line: $r", r.bottom in 120 * k..150 * k)
            // The two words read as one phrase, in order.
            assertEquals(1, doc.search(0, "hello wörld").size)
            // And the text is invisible: the page looks like the image alone.
            // (Under the second word: white, outside the image's black corner block.)
            assertTrue(Color.red(doc.renderPage(0, 500).getPixel(385 / 2, 120 / 2)) > 200)
        } finally {
            doc.close()
        }
    }

    @Test
    fun compressingShrinksBigImages() = runBlocking {
        // A 3000 x 4000 photo-like page (noisy, so JPEG can't make it tiny), at high quality.
        val bitmap = Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(1)
        val pixels = IntArray(3000 * 4000) { Color.rgb(200 + random.nextInt(40), 200 + random.nextInt(40), 190 + random.nextInt(40)) }
        bitmap.setPixels(pixels, 0, 3000, 0, 0, 3000, 4000)
        val photo = File(context.cacheDir, "big.jpg").apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } }
        val original = File(context.cacheDir, "big.pdf").apply { delete() }
        ImagePdf.write(context, listOf(photo), original)

        val small = File(context.cacheDir, "big-small.pdf").apply { delete() }
        PdfDocument.compress(original, small)
        assertTrue("${original.length()} -> ${small.length()}", small.length() < original.length() / 2)
        val doc = PdfDocument.open(context, Uri.fromFile(small))
        try {
            assertEquals(1, doc.pageCount)
            val page = doc.renderPage(0, 300)
            assertTrue("still the light photo", Color.red(page.getPixel(150, 200)) > 150)
        } finally {
            doc.close()
        }
    }

    @Test
    fun textLayerOnExistingPages() = runBlocking {
        // An image-only page (no text), then that same page turned a quarter.
        val image = image("plain.jpg", 1000, 1400, Bitmap.CompressFormat.JPEG)
        val made = File(context.cacheDir, "plain.pdf").apply { delete() }
        ImagePdf.write(context, listOf(image), made)
        val turned = File(context.cacheDir, "plain-turned.pdf").apply { delete() }
        PdfDocument.open(context, Uri.fromFile(made)).let { d ->
            d.saveRearranged(listOf(PageSpec.Existing(0), PageSpec.Existing(0, from = PdfDocument.open(context, Uri.fromFile(made)), rotation = 90)), turned)
            d.close()
        }
        val doc = PdfDocument.open(context, Uri.fromFile(turned))
        try {
            assertFalse(doc.hasText(0))
            for (page in 0..1) {
                val size = doc.pageSizes[page]
                // Pretend OCR on a 1000-pixel-wide render found a word 30% across, 20% down.
                val w = 1000
                val h = (w * size.height / size.width).toInt()
                val word = OcrWord("Szabad", (0.3f * w).toInt(), (0.2f * h).toInt(), (0.5f * w).toInt(), (0.2f * h + 40).toInt(), 40, (0.2f * h + 40).toInt())
                doc.addTextLayer(context, page, listOf(word), w, "test")
                val hit = doc.search(page, "szabad").single().first()
                val k = size.width / w
                assertEquals("page $page left", 0.3f * w * k, hit.left, 4f)
                assertEquals("page $page right", 0.5f * w * k, hit.right, 4f)
                assertTrue("page $page height", hit.bottom > hit.top)
            }
            assertTrue(doc.hasText(0))
            doc.undo() // both pages were one group
            assertFalse(doc.hasText(0))
            assertFalse(doc.hasText(1))
        } finally {
            doc.close()
        }
    }
}
