/*
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 The SzabadPDF authors
 *
 * Adapted from FairScan's OcrService (https://github.com/pynicolas/FairScan).
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.szabadpdf.core.scan

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A word found on a page image, with its box in image pixels. [lineHeight] and [lineBottom]
 * are those of its text line, which gives the words of a line one size and baseline.
 */
data class RecognizedWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val lineHeight: Int,
    val lineBottom: Int,
)

/** Reads the text on page images with Tesseract (English data bundled with the app). */
class TextRecognizer(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var api: TessBaseAPI? = null

    /** Finds the words on [page]. Words Tesseract isn't fairly sure of are left out. */
    suspend fun recognize(page: Bitmap): List<RecognizedWord> = withContext(Dispatchers.Default) {
        lock.withLock {
            val tess = engine()
            tess.setImage(page)
            tess.getUTF8Text() // runs the recognition
            val words = mutableListOf<RecognizedWord>()
            val iterator = tess.resultIterator ?: return@withLock emptyList()
            try {
                iterator.begin()
                do {
                    val text = iterator.getUTF8Text(PageIteratorLevel.RIL_WORD)?.trim()
                    if (text.isNullOrEmpty()) continue
                    if (iterator.confidence(PageIteratorLevel.RIL_WORD) <= MIN_CONFIDENCE) continue
                    val word = iterator.getBoundingRect(PageIteratorLevel.RIL_WORD)
                    val line = iterator.getBoundingRect(PageIteratorLevel.RIL_TEXTLINE)
                    words += RecognizedWord(text, word.left, word.top, word.right, word.bottom, line.height(), line.bottom)
                } while (iterator.next(PageIteratorLevel.RIL_WORD))
            } finally {
                iterator.delete()
                tess.clear()
            }
            words
        }
    }

    fun close() {
        api?.recycle()
        api = null
    }

    private fun engine(): TessBaseAPI = api ?: run {
        // Tesseract reads its data from a real directory, so it's copied out of the APK once.
        val root = File(appContext.filesDir, "ocr")
        val data = File(root, "tessdata/$LANGUAGE.traineddata")
        // Records which data was copied, so an app update with new data replaces it.
        val marker = File(root, "tessdata/$LANGUAGE.version")
        if (!data.exists() || runCatching { marker.readText() }.getOrNull() != DATA_VERSION) {
            data.parentFile?.mkdirs()
            val partial = File(data.parentFile, "${data.name}.part")
            appContext.assets.open("tessdata/$LANGUAGE.traineddata").use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            check(partial.renameTo(data)) { "Couldn't install the text recognition data" }
            marker.writeText(DATA_VERSION)
        }
        val tess = TessBaseAPI()
        if (!tess.init(root.path, LANGUAGE)) {
            tess.recycle()
            error("Text recognition failed to start")
        }
        // Whole pages, possibly with columns.
        tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        tess.also { api = it }
    }

    private companion object {
        const val LANGUAGE = "eng"

        /** The bundled data's SHA-256 (see build.gradle.kts). */
        const val DATA_VERSION = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
        const val MIN_CONFIDENCE = 50f
    }
}
