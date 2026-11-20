package org.szabadpdf.core.pdf

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Rebuilding and saving documents, against the same 12-page sample as [PdfDocumentTest]. */
@RunWith(AndroidJUnit4::class)
class EditTest {

    private lateinit var context: Context
    private lateinit var sample: File
    private lateinit var out: File
    private val opened = mutableListOf<PdfDocument>()

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        sample = File(context.cacheDir, "edit-sample.pdf")
        instrumentation.context.assets.open("sample.pdf").use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        out = File(context.cacheDir, "edit-out.pdf").apply { delete() }
    }

    @After
    fun tearDown() = opened.forEach(PdfDocument::close)

    private suspend fun open(file: File) = PdfDocument.open(context, Uri.fromFile(file)).also { opened += it }

    @Test
    fun reordersAndDropsPages() = runBlocking {
        open(sample).saveRearranged(listOf(2, 0, 5).map { PageSpec.Existing(it) }, out)
        val result = open(out)
        assertEquals(3, result.pageCount)
        assertEquals(2, result.search(0, "szabad").size) // old page 3 is now first
        assertEquals(PageSize(792f, 612f), result.pageSizes[2]) // the landscape page
        assertEquals("SzabadPDF sample", result.title())
    }

    @Test
    fun outlineFollowsMovedPages() = runBlocking {
        open(sample).saveRearranged(listOf(5, 2, 0).map { PageSpec.Existing(it) }, out)
        val pages = open(out).outline().associate { it.title to it.pageIndex }
        assertEquals(0, pages["Landscape page"])
        assertEquals(1, pages["Chapter One"])
        assertEquals(2, pages["Introduction"])
    }

    @Test
    fun rotatesPages() = runBlocking {
        open(sample).saveRearranged(
            listOf(PageSpec.Existing(0, rotation = 90), PageSpec.Existing(5, rotation = -90), PageSpec.Existing(1, rotation = 360)),
            out,
        )
        val sizes = open(out).pageSizes
        assertEquals(listOf(PageSize(792f, 612f), PageSize(612f, 792f), PageSize(612f, 792f)), sizes)
    }

    @Test
    fun rotationIsClockwise() = runBlocking {
        // The sample's page 1 has a black square in its bottom-left corner; turned clockwise
        // it ends up top-left.
        open(sample).saveRearranged(listOf(PageSpec.Existing(0, rotation = 90)), out)
        val bitmap = open(out).renderPage(0, 792)
        assertEquals(Color.BLACK, bitmap.getPixel(10, 10))
        assertEquals(Color.WHITE, bitmap.getPixel(10, 600))
    }

    @Test
    fun insertsBlankPagesAndPagesFromOtherDocuments() = runBlocking {
        val other = open(sample)
        open(sample).saveRearranged(
            listOf(
                PageSpec.Existing(0),
                PageSpec.Blank(PageSize(300f, 400f)),
                PageSpec.Existing(2, from = other),
                PageSpec.Existing(5, from = other, rotation = 90),
            ),
            out,
        )
        val result = open(out)
        assertEquals(
            listOf(PageSize(612f, 792f), PageSize(300f, 400f), PageSize(612f, 792f), PageSize(612f, 792f)),
            result.pageSizes,
        )
        val blank = result.renderPage(1, 300)
        assertEquals(Color.WHITE, blank.getPixel(150, 200))
        assertEquals(2, result.search(2, "szabad").size)
    }

    @Test
    fun replaceWritesAndTruncates() = runBlocking {
        val target = File(context.cacheDir, "replace-target.pdf")
        target.writeBytes(ByteArray(200_000) { 7 })
        val small = File(context.cacheDir, "replace-small.pdf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        DocumentFiles.replace(context, Uri.fromFile(target), small)
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertTrue(DocumentFiles.canWrite(context, Uri.fromFile(target)))
    }
}
