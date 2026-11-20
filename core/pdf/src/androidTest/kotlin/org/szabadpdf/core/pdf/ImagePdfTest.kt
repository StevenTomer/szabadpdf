package org.szabadpdf.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        ImagePdf.write(listOf(portrait, landscape), out, title = "Scan")

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
}
