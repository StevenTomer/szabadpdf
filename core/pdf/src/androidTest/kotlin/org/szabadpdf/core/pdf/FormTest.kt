package org.szabadpdf.core.pdf

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Filling in testdata/make-form.py's form (assets/form.pdf). */
@RunWith(AndroidJUnit4::class)
class FormTest {

    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var doc: PdfDocument

    @Before
    fun setUp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        file = File(context.cacheDir, "form.pdf")
        instrumentation.context.assets.open("form.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        doc = PdfDocument.open(context, Uri.fromFile(file))
    }

    @After
    fun tearDown() = doc.close()

    private suspend fun field(name: String, n: Int = 0) = doc.formFields(0).filter { it.name == name }[n]

    @Test
    fun listsTheFields() = runBlocking {
        val fields = doc.formFields(0)
        assertEquals(listOf(FieldKind.TEXT, FieldKind.CHECKBOX, FieldKind.RADIO, FieldKind.RADIO, FieldKind.CHOICE), fields.map { it.kind })
        assertEquals(listOf("Small", "Medium", "Large"), field("size").options)
        assertEquals("Medium", field("size").value)
        // Positions are from the top-left: the name field spans y 650..672 from the bottom of 792.
        assertEquals(792f - 672f, field("name").bounds.top, 0.5f)
    }

    @Test
    fun fillsInSavesAndReopens() = runBlocking {
        doc.setFieldText(0, field("name").id, "Ada Lovelace")
        doc.toggleField(0, field("agree").id)
        doc.toggleField(0, field("color", 1).id) // Blue
        doc.setFieldChoice(0, field("size").id, "Large")
        assertTrue(field("agree").checked)
        assertFalse(field("color", 0).checked)
        assertTrue(field("color", 1).checked)
        assertTrue(doc.editState().canUndo)

        doc.saveTo(context, Uri.fromFile(file))
        doc.close()
        doc = PdfDocument.open(context, Uri.fromFile(file))
        assertEquals("Ada Lovelace", field("name").value)
        assertTrue(field("agree").checked)
        assertTrue(field("color", 1).checked)
        assertEquals("Large", field("size").value)
        // The typed text is part of the page now (its appearance), so it can be found.
        assertEquals(1, doc.search(0, "Lovelace").size)
    }

    @Test
    fun fillingIsUndoable() = runBlocking {
        doc.setFieldText(0, field("name").id, "first")
        doc.setFieldText(0, field("name").id, "second")
        doc.undo()
        assertEquals("first", field("name").value)
        doc.toggleField(0, field("agree").id)
        doc.undo()
        assertFalse(field("agree").checked)
    }
}
