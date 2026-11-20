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
        val stroke = (0..20).map { PdfPoint(200f + it * 10f, 400f) } // x 200..400
        doc.addInk(0, stroke, InkStyle(0x000000, 4f))
        doc.addInk(0, stroke.map { it.copy(y = 450f) }, InkStyle(0xFF0000, 4f))
        assertEquals(2, doc.annotations(0).count { it.kind == AnnotationKind.INK })
        assertEquals(Color.BLACK, doc.renderPage(0, 612).getPixel(300, 400))

        // One eraser gesture across the middle of both strokes, in two pieces: undone as one step.
        assertNotNull(doc.erase(0, listOf(PdfPoint(300f, 390f), PdfPoint(300f, 410f)), 5f, gesture = 1))
        assertNotNull(doc.erase(0, listOf(PdfPoint(300f, 440f), PdfPoint(300f, 460f)), 5f, gesture = 1))
        assertNull(doc.erase(0, listOf(PdfPoint(50f, 50f)), 5f, gesture = 1))
        val bitmap = doc.renderPage(0, 612)
        assertEquals("rubbed out where the eraser went", Color.WHITE, bitmap.getPixel(300, 400))
        assertEquals("the rest of the stroke is still there", Color.BLACK, bitmap.getPixel(240, 400))
        assertEquals(Color.BLACK, bitmap.getPixel(360, 400))
        assertEquals("still one drawing each, now in two pieces", 2, doc.annotations(0).count { it.kind == AnnotationKind.INK })

        // Rubbing out all of a stroke removes the drawing.
        doc.erase(0, (0..40).map { PdfPoint(195f + it * 5.5f, 450f) }, 8f, gesture = 2)
        assertEquals(1, doc.annotations(0).count { it.kind == AnnotationKind.INK })

        doc.undo()
        assertEquals(2, doc.annotations(0).size)
        doc.undo()
        assertEquals(Color.BLACK, doc.renderPage(0, 612).getPixel(300, 400))
        val state = doc.redo()
        assertEquals(Color.WHITE, doc.renderPage(0, 612).getPixel(300, 400))
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

    @Test
    fun addsChangesAndRemovesPasswords() = runBlocking {
        assertFalse(doc.isEncrypted())
        doc.addNote(0, PdfPoint(400f, 100f), "secret note")
        doc.saveWithPassword(context, Uri.fromFile(file), "open sesame")
        doc.close()

        doc = PdfDocument.open(context, Uri.fromFile(file))
        assertTrue("needs the password now", doc.isLocked)
        assertFalse(doc.authenticate("wrong"))
        assertTrue(doc.authenticate("open sesame"))
        assertTrue(doc.isEncrypted())
        assertEquals("unsaved edits went in too", "secret note", doc.annotationAt(0, PdfPoint(410f, 110f))?.contents)

        // Printing copies come out unencrypted, annotations drawn in.
        val flat = File(context.cacheDir, "flat-protected.pdf").apply { delete() }
        PdfDocument.flatten(file, flat, "open sesame")
        PdfDocument.open(context, Uri.fromFile(flat)).also { extra += it }.let { assertFalse(it.isLocked) }

        doc.saveWithPassword(context, Uri.fromFile(file), "new one")
        doc.close()
        doc = PdfDocument.open(context, Uri.fromFile(file))
        assertFalse(doc.authenticate("open sesame"))
        assertTrue(doc.authenticate("new one"))

        doc.saveWithPassword(context, Uri.fromFile(file), null)
        doc.close()
        doc = PdfDocument.open(context, Uri.fromFile(file))
        assertFalse("no password any more", doc.isLocked)
        assertFalse(doc.isEncrypted())
        assertEquals(12, doc.pageCount)
    }

    @Test
    fun textBoxesAndShapes() = runBlocking {
        doc.addTextBox(1, PdfPoint(100f, 400f), "Check this figure", TextBoxStyle(color = 0xD32F2F))
        doc.addShape(1, ShapeKind.RECTANGLE, PdfPoint(100f, 500f), PdfPoint(300f, 600f), ShapeStyle(borderColor = 0x1E5BD8, borderWidth = 2f))
        doc.addShape(1, ShapeKind.ELLIPSE, PdfPoint(350f, 500f), PdfPoint(450f, 600f), ShapeStyle(borderColor = 0x1E5BD8, borderWidth = 2f))
        doc.addShape(1, ShapeKind.ARROW, PdfPoint(100f, 700f), PdfPoint(300f, 650f), ShapeStyle(borderColor = 0x000000, borderWidth = 2f))
        val kinds = doc.annotations(1).map { it.kind }
        assertEquals(listOf(AnnotationKind.TEXT_BOX, AnnotationKind.SHAPE, AnnotationKind.SHAPE, AnnotationKind.SHAPE), kinds)
        val box = doc.annotations(1).first()
        assertEquals("Check this figure", box.contents)
        assertTrue("text box starts at the tap: ${box.bounds}", kotlin.math.abs(box.bounds.left - 100f) < 2f && kotlin.math.abs(box.bounds.top - 400f) < 2f)
        // The text box's text is drawn on the page and can be found.
        assertEquals(1, doc.search(1, "this figure").size)
        // The rectangle's outline is drawn (left edge at x = 100).
        val bitmap = doc.renderPage(1, 612)
        assertNotEquals(Color.WHITE, bitmap.getPixel(100, 550))
        assertEquals(Color.WHITE, bitmap.getPixel(200, 550))
    }

    @Test
    fun editsDocumentProperties() = runBlocking {
        assertEquals("SzabadPDF sample", doc.properties().title)
        val state = doc.setProperties(DocumentProperties(title = "Meeting notes", author = "Me", subject = "Plans"))
        assertTrue(state.canUndo)
        assertEquals("Meeting notes", doc.title())
        doc.saveTo(context, Uri.fromFile(file))
        doc.close()
        doc = PdfDocument.open(context, Uri.fromFile(file))
        assertEquals(DocumentProperties("Meeting notes", "Me", "Plans", ""), doc.properties())
        doc.setProperties(DocumentProperties(title = "Changed"))
        doc.undo()
        assertEquals("Meeting notes", doc.title())
    }

    @Test
    fun styledTextBoxes() = runBlocking {
        val style = TextBoxStyle(
            font = TextFont.SERIF, size = 20f, bold = true, italic = true, underline = true, color = 0xD32F2F,
            border = true, borderWidth = 2f, borderColor = 0x1E5BD8, background = true, backgroundColor = 0xFFF59D,
        )
        doc.addTextBox(1, PdfPoint(100f, 400f), "Árvíztűrő tükörfúrógép\nsecond line", style)
        val box = doc.annotations(1).single { it.kind == AnnotationKind.TEXT_BOX }
        assertEquals(style, doc.textBoxStyle(1, box.id))
        assertEquals("accented text can be found", 1, doc.search(1, "tükörfúrógép").size)
        val b = box.bounds
        val bitmap = doc.renderPage(1, 612)
        assertNear("border", 0x1E5BD8, bitmap.getPixel(b.left.toInt() + 1, ((b.top + b.bottom) / 2).toInt()))
        assertNear("background", 0xFFF59D, bitmap.getPixel(b.right.toInt() - 6, b.bottom.toInt() - 6))

        // Bold text is wider than regular.
        doc.updateTextBox(1, box.id, "Árvíztűrő tükörfúrógép\nsecond line", style.copy(bold = false))
        val regular = doc.annotations(1).single { it.kind == AnnotationKind.TEXT_BOX }
        assertTrue("bold ${b.right - b.left} vs regular ${regular.bounds.right - regular.bounds.left}", b.right - b.left > regular.bounds.right - regular.bounds.left)
        assertEquals("stays where it was", b.left, regular.bounds.left, 0.5f)
        assertEquals(b.top, regular.bounds.top, 0.5f)

        // Survives saving and reopening, look and all.
        doc.saveTo(context, Uri.fromFile(file))
        doc.close()
        doc = PdfDocument.open(context, Uri.fromFile(file))
        val reopened = doc.annotations(1).single { it.kind == AnnotationKind.TEXT_BOX }
        assertEquals(style.copy(bold = false), doc.textBoxStyle(1, reopened.id))
        assertNear("background after reopening", 0xFFF59D, doc.renderPage(1, 612).getPixel(reopened.bounds.right.toInt() - 6, reopened.bounds.bottom.toInt() - 6))
    }

    @Test
    fun filledShapesWithAndWithoutOutline() = runBlocking {
        doc.addShape(1, ShapeKind.RECTANGLE, PdfPoint(100f, 500f), PdfPoint(300f, 600f),
            ShapeStyle(border = true, borderColor = 0x000000, borderWidth = 3f, fill = true, fillColor = 0x2E7D32))
        doc.addShape(1, ShapeKind.ELLIPSE, PdfPoint(350f, 500f), PdfPoint(500f, 600f),
            ShapeStyle(border = false, fill = true, fillColor = 0xD32F2F))
        val bitmap = doc.renderPage(1, 612)
        assertNear("filled", 0x2E7D32, bitmap.getPixel(200, 550))
        // The outline straddles the edge at x = 100.
        assertNear("outlined", 0x000000, (95..105).map { bitmap.getPixel(it, 550) }.minBy { Color.red(it) + Color.green(it) + Color.blue(it) })
        assertNear("oval filled", 0xD32F2F, bitmap.getPixel(425, 550))
        assertNear("oval has no outline", 0xD32F2F, bitmap.getPixel(352, 550))
    }

    /** [actual] (ARGB) is [expected] (0xRRGGBB), give or take rounding. */
    private fun assertNear(what: String, expected: Int, actual: Int) {
        val close = listOf(16, 8, 0).all { shift -> kotlin.math.abs(((expected shr shift) and 0xFF) - ((actual shr shift) and 0xFF)) <= 8 }
        assertTrue("$what: expected %06x, got %06x".format(expected, actual and 0xFFFFFF), close)
    }
}
