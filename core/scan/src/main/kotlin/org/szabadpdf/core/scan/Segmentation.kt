/*
 * Copyright 2025-2026 The FairScan authors
 * Copyright 2026 The SzabadPDF authors
 *
 * Adapted from FairScan (https://github.com/pynicolas/FairScan).
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
import org.fairscan.imageprocessing.Mask
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs the segmentation model: for each pixel, how likely it is to be part of the document. */
internal class Segmentation(context: Context) {

    private val interpreter: Interpreter
    private val inputWidth: Int
    private val inputHeight: Int

    init {
        val bytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        model.rewind()
        interpreter = Interpreter(model, Interpreter.Options().apply { numThreads = 2 })
        val (_, h, w, _) = interpreter.getOutputTensor(0).shape()
        inputWidth = w
        inputHeight = h
    }

    /** Not thread-safe; callers serialize. */
    fun run(bitmap: Bitmap): ProbabilityMask {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val pixels = IntArray(inputWidth * inputHeight)
        scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (scaled !== bitmap) scaled.recycle()

        // RGB scaled to [-1, 1], as the model was trained.
        val input = ByteBuffer.allocateDirect(4 * 3 * pixels.size).order(ByteOrder.nativeOrder())
        for (p in pixels) {
            input.putFloat((((p shr 16) and 0xFF) - 127.5f) / 127.5f)
            input.putFloat((((p shr 8) and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((p and 0xFF) - 127.5f) / 127.5f)
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(4 * pixels.size).order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        val probabilities = FloatArray(pixels.size)
        output.asFloatBuffer().get(probabilities)
        for (i in probabilities.indices) probabilities[i] = probabilities[i].coerceIn(0f, 1f)
        return ProbabilityMask(probabilities, inputWidth, inputHeight)
    }

    fun close() = interpreter.close()

    class ProbabilityMask(private val values: FloatArray, override val width: Int, override val height: Int) : Mask {
        override fun toMat(): Mat {
            val mat = Mat(height, width, CvType.CV_8UC1)
            mat.put(0, 0, ByteArray(values.size) { if (values[it] >= 0.5f) 255.toByte() else 0 })
            return mat
        }
    }

    companion object {
        const val MODEL_FILE = "fairscan-segmentation-model.tflite"
    }
}
