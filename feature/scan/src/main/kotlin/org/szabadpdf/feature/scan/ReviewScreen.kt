package org.szabadpdf.feature.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.szabadpdf.core.scan.Corners
import org.szabadpdf.core.scan.ScanColor
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Swipe through the scanned pages, fix any of them, then save as PDF. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewScreen(
    state: ScanState,
    viewModel: ScanViewModel,
    onAddMore: () -> Unit,
    onSave: () -> Unit,
) {
    val pages = state.pages
    val pager = rememberPagerState { pages.size }
    var cropping by remember { mutableStateOf<ScanPage?>(null) }
    val current = pages.getOrNull(pager.currentPage)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onAddMore) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to camera") } },
            title = { Text(if (pages.size == 1) "1 page" else "${pages.size} pages") },
            actions = {
                IconButton(onClick = onAddMore) { Icon(Icons.Filled.AddAPhoto, "Scan more pages") }
                TextButton(onClick = onSave, enabled = pages.isNotEmpty() && state.processing == 0) { Text("Save PDF") }
            },
        )
        HorizontalPager(
            state = pager,
            key = { pages[it].id },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
            pageSpacing = 16.dp,
        ) { index ->
            val page = pages[index]
            Box(Modifier.fillMaxSize().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                val bitmap = rememberPageBitmap(page.result, page.version, 1600)
                if (bitmap == null) {
                    CircularProgressIndicator()
                } else {
                    Image(
                        bitmap.asImageBitmap(),
                        contentDescription = "Page ${index + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            if (pages.isEmpty()) "" else "${pager.currentPage + 1} / ${pages.size}" +
                if (current?.detected == false) " · page edges not found, use Crop" else "",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                val enabled = current != null
                IconButton(onClick = { current?.let { viewModel.move(it.id, -1) } }, enabled = enabled && pager.currentPage > 0) {
                    Icon(Icons.Filled.ChevronLeft, "Move page earlier")
                }
                IconButton(onClick = { cropping = current }, enabled = enabled) { Icon(Icons.Filled.Crop, "Crop") }
                IconButton(onClick = { current?.let { viewModel.rotate(it.id) } }, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate")
                }
                ColorMenu(current, viewModel)
                IconButton(onClick = { current?.let { viewModel.delete(it.id) } }, enabled = enabled) {
                    Icon(Icons.Filled.Delete, "Delete page")
                }
                IconButton(
                    onClick = { current?.let { viewModel.move(it.id, 1) } },
                    enabled = enabled && pager.currentPage < pages.size - 1,
                ) { Icon(Icons.Filled.ChevronRight, "Move page later") }
            }
        }
    }

    cropping?.let { page ->
        CropEditor(
            page = page,
            onApply = { corners ->
                viewModel.setCorners(page.id, corners)
                cropping = null
            },
            onCancel = { cropping = null },
        )
    }
}

@Composable
private fun ColorMenu(page: ScanPage?, viewModel: ScanViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = page != null) { Icon(Icons.Filled.Palette, "Colour") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((color, label) in listOf(
                ScanColor.COLOR to "Colour",
                ScanColor.GRAYSCALE to "Greyscale",
                ScanColor.BLACK_AND_WHITE to "Black and white",
            )) {
                DropdownMenuItem(
                    text = { Text(label + if (color == page?.suggestedColor) " (suggested)" else "") },
                    leadingIcon = { if (color == page?.color) Icon(Icons.Filled.Check, null) },
                    onClick = {
                        open = false
                        page?.let { viewModel.setColor(it.id, color) }
                    },
                )
            }
        }
    }
}

/** Drag the four corners onto the page's edges in the original photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CropEditor(page: ScanPage, onApply: (Corners) -> Unit, onCancel: () -> Unit) {
    val photo = rememberPageBitmap(page.source, 0, 2048)
    var corners by remember(page.id) { mutableStateOf(page.corners.points) }
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") } },
            title = { Text("Crop") },
            actions = {
                TextButton(onClick = { corners = Corners.WHOLE_IMAGE.points }) { Text("Whole photo") }
                TextButton(onClick = {
                    onApply(Corners(corners[0], corners[1], corners[2], corners[3]))
                }) { Text("Apply") }
            },
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            val image = photo ?: return@BoxWithConstraints CircularProgressIndicator()
            // Where the photo sits inside this box (fitted, centred).
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val scale = min(boxW / image.width, boxH / image.height)
            val w = image.width * scale
            val h = image.height * scale
            val left = (boxW - w) / 2
            val top = (boxH - h) / 2
            fun toView(p: PointF) = Offset(left + p.x * w, top + p.y * h)
            val reach = with(density) { 40.dp.toPx() }

            Image(
                image.asImageBitmap(),
                contentDescription = "Photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(page.id, w, h) {
                        var dragging = -1
                        detectDragGestures(
                            onDragStart = { at ->
                                val nearest = corners.indices.minBy { hypot(toView(corners[it]).x - at.x, toView(corners[it]).y - at.y) }
                                val d = toView(corners[nearest]).let { hypot(it.x - at.x, it.y - at.y) }
                                dragging = if (d < reach) nearest else -1
                            },
                            onDrag = { change, amount ->
                                if (dragging < 0) return@detectDragGestures
                                change.consume()
                                val p = corners[dragging]
                                val moved = PointF(
                                    (p.x + amount.x / w).coerceIn(0f, 1f),
                                    (p.y + amount.y / h).coerceIn(0f, 1f),
                                )
                                corners = corners.toMutableList().also { it[dragging] = moved }
                            },
                            onDragEnd = { dragging = -1 },
                        )
                    },
            ) {
                val points = corners.map(::toView)
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                drawPath(path, Color(0x334CAF50))
                drawPath(path, Color(0xFF4CAF50), style = Stroke(2.dp.toPx()))
                for (p in points) {
                    drawCircle(Color.White, 14.dp.toPx(), p)
                    drawCircle(Color(0xFF4CAF50), 10.dp.toPx(), p)
                }
            }
        }
        Text(
            "Drag the corners onto the page's corners",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .navigationBarsPadding()
                .padding(16.dp),
        )
    }
}

/** Loads an image file scaled down to at most [maxSide] pixels; reloads when [version] changes. */
@Composable
internal fun rememberPageBitmap(file: File, version: Int, maxSide: Int): Bitmap? {
    val bitmap by produceState<Bitmap?>(null, file, version, maxSide) {
        value = withContext(Dispatchers.IO) { decodeScaled(file, maxSide) }
    }
    return bitmap
}

private fun decodeScaled(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}
