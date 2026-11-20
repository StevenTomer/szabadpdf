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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Text selection, annotations, undo and saving, against the 12-page sample. */
@RunWith(AndroidJUnit4::class)
class AnnotateTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var doc: PdfDocument
    private val extra = mutableListOf<PdfDocument>()

    @Before
    fun setUp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        file = File(context.cacheDir, "annotate-sample.pdf")
        instrumentation.context.assets.open("sample.pdf").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        doc = PdfDocument.open(context, Uri.fromFile(file))
    }

    @After
    fun tearDown() {
        doc.close()
        extra.forEach(PdfDocument::close)
    }

    /** Centre of the first "Szabad" on page 3. */
    private suspend fun szabad(): PdfPoint {
        val r = doc.search(2, "Szabad")[0][0]
        return PdfPoint((r.left + r.right) / 2, (r.top + r.bottom) / 2)
    }

    @Test
    fun selectsWordsAndRanges() = runBlocking {
        val word = doc.selectWord(2, szabad())
        assertNotNull(word)
        assertEquals("Szabad", word!!.text)
        val line = doc.selectText(2, word.start, PdfPoint(word.end.x + 120f, word.end.y))
        assertTrue(line!!.text, line.text.startsWith("Szabad means free"))
        assertNull(doc.selectWord(2, PdfPoint(300f, 700f))) // empty paper
    }

    @Test
    fun highlightsText() = runBlocking {
        val word = doc.selectWord(2, szabad())!!
        val state = doc.addMarkup(2, word.start, word.end, MarkupKind.HIGHLIGHT, 0xFFEB3B)
        assertTrue(state.canUndo)
        val annots = doc.annotations(2)
        assertEquals(listOf(AnnotationKind.HIGHLIGHT), annots.map { it.kind })
        val p = szabad()
        val bitmap = doc.renderPage(2, 612)
        assertNotEquals(Color.WHITE, bitmap.getPixel(p.x.toInt(), (word.rects[0].top + 1).toInt()))
    }

    @Test
    fun drawsErasesAndUndoesInk() = runBlocking {
        val stroke = (0..20).map { PdfPoint(200f + it * 10f, 400f) }
        doc.addInk(0, stroke, InkStyle(0x000000, 4f))
        doc.addInk(0, stroke.map { it.copy(y = 450f) }, InkStyle(0xFF0000, 4f))
        assertEquals(2, doc.annotations(0).count { it.kind == AnnotationKind.INK })
        assertEquals(Color.BLACK, doc.renderPage(0, 612).getPixel(300, 400))

        // One eraser gesture over both strokes, delivered in two pieces: undone as one step.
        assertNotNull(doc.erase(0, listOf(PdfPoint(300f, 390f), PdfPoint(300f, 410f)), 5f, gesture = 1))
        assertNotNull(doc.erase(0, listOf(PdfPoint(300f, 440f), PdfPoint(300f, 460f)), 5f, gesture = 1))
        assertNull(doc.erase(0, listOf(PdfPoint(50f, 50f)), 5f, gesture = 1))
        assertTrue(doc.annotations(0).isEmpty())
        assertEquals(Color.WHITE, doc.renderPage(0, 612).getPixel(300, 400))

        doc.undo()
        assertEquals(2, doc.annotations(0).size)
        doc.undo()
        assertEquals(1, doc.annotations(0).size)
        val state = doc.redo()
        assertEquals(2, doc.annotations(0).size)
        assertTrue(state.canRedo)
    }

    @Test
    fun notesCanBeFoundEditedAndDeleted() = runBlocking {
        doc.addNote(1, PdfPoint(100f, 100f), "first")
        val note = doc.annotationAt(1, PdfPoint(110f, 110f))
        assertEquals(AnnotationKind.NOTE, note!!.kind)
        assertEquals("first", note.contents)
        doc.setContents(1, note.id, "second")
        assertEquals("second", doc.annotationAt(1, PdfPoint(110f, 110f))!!.contents)
        val state = doc.deleteAnnotation(1, note.id)
        assertNull(doc.annotationAt(1, PdfPoint(110f, 110f)))
        assertTrue(state.canUndo)
    }

    @Test
    fun savesByAppendingAndReopens() = runBlocking {
        val original = file.readBytes()
        doc.addInk(0, listOf(PdfPoint(100f, 300f), PdfPoint(200f, 320f)), InkStyle(0x0000FF, 2f))
        doc.addNote(0, PdfPoint(400f, 100f), "hello")
        doc.saveTo(context, Uri.fromFile(file))
        doc.close()

        val saved = file.readBytes()
        assertTrue(saved.size > original.size)
        assertArrayEquals("changes are appended after the original bytes", original, saved.copyOf(original.size))

        doc = PdfDocument.open(context, Uri.fromFile(file))
        assertEquals(12, doc.pageCount)
        assertEquals(setOf(AnnotationKind.INK, AnnotationKind.NOTE), doc.annotations(0).map { it.kind }.toSet())
        assertFalse(doc.editState().canUndo)
    }

    @Test
    fun flattenDrawsAnnotationsIntoThePage() = runBlocking {
        val word = doc.selectWord(2, szabad())!!
        doc.addMarkup(2, word.start, word.end, MarkupKind.HIGHLIGHT, 0xFFEB3B)
        val copy = File(context.cacheDir, "annotated-copy.pdf").apply { delete() }
        doc.saveTo(context, Uri.fromFile(copy))
        val flat = File(context.cacheDir, "flat.pdf").apply { delete() }
        PdfDocument.flatten(copy, flat)

        val result = PdfDocument.open(context, Uri.fromFile(flat)).also { extra += it }
        assertEquals(12, result.pageCount)
        assertTrue("no annotations left", result.annotations(2).isEmpty())
        // ...but the highlight is still there on the page.
        val y = (word.rects[0].top + 1).toInt()
        val x = ((word.rects[0].left + word.rects[0].right) / 2).toInt()
        assertNotEquals(Color.WHITE, result.renderPage(2, 612).getPixel(x, y))
    }

    @Test
    fun draftsKeepUnsavedEdits() = runBlocking {
        val draft = File(context.cacheDir, "draft.pdf").apply { delete() }
        val before = DocumentFiles.fingerprint(context, Uri.fromFile(file))
        doc.saveDraft(draft)
        assertFalse("no draft without edits", draft.exists())

        doc.addInk(0, listOf(PdfPoint(100f, 300f), PdfPoint(200f, 320f)), InkStyle(0x0000FF, 2f))
        doc.addNote(0, PdfPoint(400f, 100f), "remember me")
        assertTrue(doc.hasUnsavedChanges())
        doc.saveDraft(draft)
        doc.close()

        // As if the app had been closed: the file itself is unchanged...
        assertEquals(before, DocumentFiles.fingerprint(context, Uri.fromFile(file)))
        // ...and the draft has the edits.
        doc = PdfDocument.open(context, Uri.fromFile(draft))
        assertEquals("remember me", doc.annotationAt(0, PdfPoint(410f, 110f))?.contents)
        assertEquals(1, doc.annotations(0).count { it.kind == AnnotationKind.INK })
        assertFalse(doc.hasUnsavedChanges())

        // Saving from the draft gives the original file the edits.
        doc.saveTo(context, Uri.fromFile(file))
        doc.close()
        assertNotEquals(before, DocumentFiles.fingerprint(context, Uri.fromFile(file)))
        doc = PdfDocument.open(context, Uri.fromFile(file))
        assertEquals("remember me", doc.annotationAt(0, PdfPoint(410f, 110f))?.contents)
    }
}
