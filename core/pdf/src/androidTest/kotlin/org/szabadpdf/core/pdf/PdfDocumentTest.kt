package org.szabadpdf.core.pdf

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs against testdata/sample.ps (built into assets/sample.pdf). */
@RunWith(AndroidJUnit4::class)
class PdfDocumentTest {

    private lateinit var context: Context
    private lateinit var doc: PdfDocument

    @Before
    fun setUp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        val file = File(context.cacheDir, "sample.pdf")
        instrumentation.context.assets.open("sample.pdf").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        doc = PdfDocument.open(context, Uri.fromFile(file))
    }

    @After
    fun tearDown() = doc.close()

    @Test
    fun opensWithPagesAndSizes() = runBlocking {
        assertFalse(doc.isLocked)
        assertEquals(12, doc.pageCount)
        assertEquals(PageSize(612f, 792f), doc.pageSizes[0])
        assertEquals(PageSize(792f, 612f), doc.pageSizes[5])
        assertEquals("SzabadPDF sample", doc.title())
    }

    @Test
    fun rendersPage() = runBlocking {
        val bitmap = doc.renderPage(0, 612)
        assertEquals(612, bitmap.width)
        assertEquals(792, bitmap.height)
        // Black square in the bottom-left corner, white paper elsewhere.
        assertEquals(Color.BLACK, bitmap.getPixel(10, 782))
        assertEquals(Color.WHITE, bitmap.getPixel(600, 10))
    }

    @Test
    fun regionMatchesSameAreaOfFullRender() = runBlocking {
        val full = doc.renderPage(0, 1224) // 2x
        val region = doc.renderRegion(0, 1224, 1584, x = 100, y = 1400, width = 200, height = 150)
        assertEquals(200, region.width)
        for ((rx, ry) in listOf(5 to 5, 30 to 140, 150 to 100, 199 to 149)) {
            assertEquals("pixel $rx,$ry", full.getPixel(100 + rx, 1400 + ry), region.getPixel(rx, ry))
        }
    }

    @Test
    fun searchFindsHitsIgnoringCase() = runBlocking {
        val hits = doc.search(2, "SZABAD")
        assertEquals(2, hits.size)
        val r = hits[0][0]
        assertTrue("hit should be near the top of the page: $r", r.top in 100f..250f)
        assertTrue(doc.search(0, "szabad").isEmpty())
    }

    @Test
    fun linksResolve() = runBlocking {
        // "Go to page 5" sits at y = 720 - 100 from the bottom in PostScript, i.e. ~176pt from the top.
        assertEquals(LinkTarget.Page(4), doc.linkAt(0, 100f, 170f))
        assertEquals(LinkTarget.External("https://example.org"), doc.linkAt(0, 100f, 200f))
        assertNull(doc.linkAt(0, 500f, 500f))
    }

    @Test
    fun outlineIsFlattenedWithDepthAndPages() = runBlocking {
        val outline = doc.outline()
        assertEquals(
            listOf(
                OutlineEntry("Introduction", 0, 0),
                OutlineEntry("Chapter One", 0, 2),
                OutlineEntry("Section 1.1", 1, 2),
                OutlineEntry("Section 1.2", 1, 3),
                OutlineEntry("Landscape page", 0, 5),
                OutlineEntry("The end", 0, 11),
            ),
            outline,
        )
    }
}
