package org.szabadpdf.feature.viewer

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import org.szabadpdf.core.pdf.PdfPoint
import kotlin.math.max

/** A drawn signature: strokes in fractions of its box, which is [aspect] times wider than tall. */
data class Signature(val strokes: List<List<PdfPoint>>, val aspect: Float)

enum class SignatureSize(val label: String, val widthPt: Float) {
    SMALL("Small", 110f),
    MEDIUM("Medium", 160f),
    LARGE("Large", 220f),
}

/** The user's signature, kept privately on the device. */
internal class SignatureStore(context: Context) {
    private val prefs = context.getSharedPreferences("signature", Context.MODE_PRIVATE)

    fun load(): Signature? = runCatching {
        val json = JSONObject(prefs.getString("signature", null) ?: return null)
        val strokes = json.getJSONArray("strokes")
        Signature(
            List(strokes.length()) { i ->
                val points = strokes.getJSONArray(i)
                List(points.length() / 2) { j -> PdfPoint(points.getDouble(2 * j).toFloat(), points.getDouble(2 * j + 1).toFloat()) }
            },
            json.getDouble("aspect").toFloat(),
        )
    }.getOrNull()

    fun save(signature: Signature) {
        val strokes = JSONArray()
        for (stroke in signature.strokes) {
            strokes.put(JSONArray().apply { stroke.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } })
        }
        prefs.edit().putString("signature", JSONObject().put("aspect", signature.aspect.toDouble()).put("strokes", strokes).toString()).apply()
    }

    var size: SignatureSize
        get() = runCatching { SignatureSize.valueOf(prefs.getString("size", null)!!) }.getOrDefault(SignatureSize.MEDIUM)
        set(value) = prefs.edit().putString("size", value.name).apply()
}

/** Draw (or redraw) the signature, and pick the size it's placed at. */
@Composable
internal fun SignaturePad(
    existing: Signature?,
    initialSize: SignatureSize,
    onSave: (Signature?, SignatureSize) -> Unit,
    onDismiss: () -> Unit,
) {
    // Strokes in pad pixels while drawing; an existing signature is shown until cleared.
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var keepExisting by remember { mutableStateOf(existing != null) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var chosenSize by remember { mutableStateOf(initialSize) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(16.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your signature", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (keepExisting) "Tap Clear to draw a new one." else "Sign in the box with your finger or pen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.5f)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .onSizeChanged { padSize = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    if (keepExisting) keepExisting = false
                                    current = listOf(it)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current = current + change.position
                                },
                                onDragEnd = {
                                    if (current.isNotEmpty()) strokes += current
                                    current = emptyList()
                                },
                            )
                        },
                ) {
                    Canvas(Modifier.matchParentSize()) {
                        val ink = Color(0xFF000000.toInt() or SIGNATURE_COLOR)
                        val style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        fun draw(points: List<Offset>) {
                            if (points.size < 2) return
                            drawPath(Path().apply {
                                moveTo(points[0].x, points[0].y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                            }, ink, style = style)
                        }
                        if (keepExisting && existing != null) {
                            // Fit the stored signature into the pad.
                            val w = min(size.width * 0.9f, size.height * 0.8f * existing.aspect)
                            val h = w / existing.aspect
                            val left = (size.width - w) / 2
                            val top = (size.height - h) / 2
                            existing.strokes.forEach { stroke -> draw(stroke.map { Offset(left + it.x * w, top + it.y * h) }) }
                        }
                        strokes.forEach(::draw)
                        draw(current)
                    }
                    Text(
                        "×  ________________________",
                        color = Color(0xFFB0B0B0),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 12.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Size", style = MaterialTheme.typography.labelLarge)
                    for (option in SignatureSize.entries) {
                        FilterChip(selected = option == chosenSize, onClick = { chosenSize = option }, label = { Text(option.label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        keepExisting = false
                        strokes.clear()
                    }) { Text("Clear") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = { onSave(if (keepExisting) existing else normalize(strokes), chosenSize) },
                        enabled = keepExisting || strokes.isNotEmpty(),
                    ) { Text("Save") }
                }
            }
        }
    }
}

/** Fits the drawn strokes to their own bounding box. */
private fun normalize(strokes: List<List<Offset>>): Signature? {
    val all = strokes.flatten()
    if (all.isEmpty()) return null
    val minX = all.minOf { it.x }
    val maxX = all.maxOf { it.x }
    val minY = all.minOf { it.y }
    val maxY = all.maxOf { it.y }
    val w = max(maxX - minX, 1f)
    val h = max(maxY - minY, 1f)
    return Signature(
        strokes.map { stroke -> stroke.map { PdfPoint((it.x - minX) / w, (it.y - minY) / h) } },
        w / h,
    )
}

private fun min(a: Float, b: Float) = kotlin.math.min(a, b)

/** Dark blue, like a ballpoint pen. */
internal const val SIGNATURE_COLOR = 0x1A237E
