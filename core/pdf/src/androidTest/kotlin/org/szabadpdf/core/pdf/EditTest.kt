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

    @Test
    fun duplicatesPagesWithTheirAnnotations() = runBlocking {
        // A copy of the sample with a drawing on its first page.
        val annotated = File(context.cacheDir, "edit-annotated.pdf").apply { delete() }
        open(sample).apply {
            addInk(0, listOf(PdfPoint(100f, 300f), PdfPoint(300f, 320f)), InkStyle(0x0000FF, 3f))
            saveTo(context, Uri.fromFile(annotated))
        }
        // Page 1, then a turned copy of it, then page 2. The copy comes from a second instance.
        val second = open(annotated)
        open(annotated).saveRearranged(
            listOf(
                PageSpec.Existing(0),
                PageSpec.Existing(0, from = second, rotation = 90),
                PageSpec.Existing(1),
                PageSpec.Existing(0, from = second), // a second copy: its own annotations again
            ),
            out,
        )
        val result = open(out)
        assertEquals(
            listOf(PageSize(612f, 792f), PageSize(792f, 612f), PageSize(612f, 792f), PageSize(612f, 792f)),
            result.pageSizes,
        )
        assertEquals("drawing on the original", 1, result.annotations(0).count { it.kind == AnnotationKind.INK })
        assertEquals("drawing on the copy", 1, result.annotations(1).count { it.kind == AnnotationKind.INK })
        suspend fun ink(page: Int) = result.annotations(page).filter { it.kind == AnnotationKind.INK }
        assertEquals("three separate drawings", 3, setOf(ink(0)[0].id, ink(1)[0].id, ink(3)[0].id).size)
        assertTrue("links aren't copied", result.annotations(1).all { it.kind == AnnotationKind.INK })
        assertEquals("text is on the copy too", result.search(0, "Page 1").size, result.search(1, "Page 1").size)
        assertTrue(ink(2).isEmpty())
        // The copy's drawing can be removed without touching the original's.
        result.deleteAnnotation(1, ink(1)[0].id)
        assertEquals(1, ink(0).size)
        assertTrue(ink(1).isEmpty())
    }

    @Test
    fun mergesDocuments() = runBlocking {
        val other = open(sample)
        val base = open(sample)
        base.saveRearranged((0 until 12).map { PageSpec.Existing(it) } + (0 until 12).map { PageSpec.Existing(it, from = other) }, out)
        val result = open(out)
        assertEquals(24, result.pageCount)
        assertEquals(PageSize(792f, 612f), result.pageSizes[17]) // the second copy's landscape page
        assertEquals(2, result.search(14, "szabad").size)
        assertEquals("SzabadPDF sample", result.title())
    }

    @Test
    fun flipsPagesWithTheirAnnotations() = runBlocking {
        // Page 1 has a black square in its bottom-left corner; add a drawing near the top left.
        val annotated = File(context.cacheDir, "edit-flip.pdf").apply { delete() }
        open(sample).apply {
            addInk(0, listOf(PdfPoint(60f, 100f), PdfPoint(160f, 100f)), InkStyle(0xFF0000, 6f))
            saveTo(context, Uri.fromFile(annotated))
        }
        open(annotated).saveRearranged(
            listOf(PageSpec.Existing(0, flipH = true), PageSpec.Existing(1, flipV = true), PageSpec.Existing(0, rotation = 90, flipH = true).let {
                it.copy(from = open(annotated))
            }),
            out,
        )
        val result = open(out)
        val flipped = result.renderPage(0, 612)
        assertEquals("square now bottom right", Color.BLACK, flipped.getPixel(602, 782))
        assertEquals(Color.WHITE, flipped.getPixel(10, 782))
        val ink = result.annotations(0).single { it.kind == AnnotationKind.INK }.bounds
        assertTrue("drawing mirrored: $ink", ink.left > 430f && ink.right < 570f && ink.top < 110f)
        assertTrue("drawing is drawn there", flipped.getPixel(500, 100).let { Color.red(it) > 200 && Color.green(it) < 100 })
        assertEquals("text still found", 1, result.search(0, "Page 1").size)

        // Upside down: page 2's heading is now at the bottom.
        val heading = result.search(1, "Page 2")[0][0]
        assertTrue("heading at the bottom: $heading", heading.top > 600f)

        // Flipped, then turned clockwise: the square goes bottom right -> bottom left.
        val turned = result.renderPage(2, 792)
        assertEquals(PageSize(792f, 612f), result.pageSizes[2])
        assertEquals(Color.BLACK, turned.getPixel(10, 602))
    }
}
