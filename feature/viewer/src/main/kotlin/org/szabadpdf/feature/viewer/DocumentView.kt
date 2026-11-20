package org.szabadpdf.feature.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.szabadpdf.core.pdf.PageSize
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A sharp, screen-resolution render of the visible part of one page, in page-item pixels. */
private class DetailTile(val left: Float, val top: Float, val width: Float, val height: Float, val bitmap: Bitmap)

internal val PageGap = 8.dp
private val HighlightColor = Color(0x66FFD54F)
private val CurrentHighlightColor = Color(0x88FF7043)

/**
 * Continuous vertical list of pages with pinch/double-tap zoom.
 *
 * Pages are rendered once at screen width. When zoomed in and still, the visible part of each
 * page is re-rendered at full resolution on top, so text stays crisp at any zoom level without
 * allocating huge bitmaps.
 */
@Composable
internal fun DocumentView(
    pageSizes: List<PageSize>,
    source: PageSource,
    listState: LazyListState,
    zoom: ZoomState,
    contentPadding: PaddingValues,
    searchHits: List<SearchHit>,
    currentHit: SearchHit?,
    onTap: (page: Int, xPt: Float, yPt: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var tiles by remember { mutableStateOf<Map<Int, DetailTile>>(emptyMap()) }
    val hitsByPage = remember(searchHits) { searchHits.groupBy { it.page } }

    // When the list hits its end while zoomed, keep moving through the zoom slack instead.
    val overscrollToPan = remember(zoom) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!zoom.isZoomed || available.y == 0f) return Offset.Zero
                val leftover = zoom.panBy(Offset(0f, available.y * zoom.scale))
                return Offset(0f, available.y - leftover / zoom.scale)
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clipToBounds()
            .onSizeChanged { zoom.viewport = it }
            // Two fingers: pinch-zoom and pan. Runs before the list sees the events.
            .pointerInput(zoom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            zoom.gestureActive = true
                            zoom.zoomBy(event.calculateZoom(), event.calculateCentroid(useCurrent = true))
                            val leftover = zoom.panBy(event.calculatePan())
                            if (leftover != 0f) listState.dispatchRawDelta(-leftover / zoom.scale)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    zoom.gestureActive = false
                }
            }
            // One finger: the list handles vertical scrolling, this handles sideways (when zoomed).
            // Once a drag passes the touch slop it's consumed, so lifting the finger isn't a tap.
            // (Parents see the Final pass before children, so the pages' tap detectors notice.)
            .pointerInput(zoom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    var travelled = Offset.Zero
                    var dragging = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size == 1 && !zoom.gestureActive) {
                            val delta = pressed[0].positionChangeIgnoreConsumed()
                            travelled += delta
                            if (!dragging && travelled.getDistance() > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                if (zoom.isZoomed) zoom.panBy(Offset(delta.x, 0f))
                                pressed[0].consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .nestedScroll(overscrollToPan),
    ) {
        val itemWidthPx = constraints.maxWidth

        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(PageGap),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = zoom.scale
                    scaleY = zoom.scale
                    translationX = zoom.offsetX
                    translationY = zoom.offsetY
                },
        ) {
            items(count = pageSizes.size, key = { it }) { index ->
                val size = pageSizes[index]
                PageItem(
                    index = index,
                    size = size,
                    widthPx = itemWidthPx,
                    source = source,
                    tile = tiles[index],
                    hits = hitsByPage[index].orEmpty(),
                    currentHit = currentHit?.takeIf { it.page == index },
                    onTap = { local ->
                        val ptPerPx = size.width / itemWidthPx
                        onTap(index, local.x * ptPerPx, local.y * ptPerPx)
                    },
                    onDoubleTap = { local ->
                        val info = listState.layoutInfo
                        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@PageItem
                        val drawnTop = (item.offset - info.viewportStartOffset).toFloat()
                        val focus = Offset(
                            local.x * zoom.scale + zoom.offsetX,
                            (drawnTop + local.y) * zoom.scale + zoom.offsetY,
                        )
                        val target = if (zoom.isZoomed) ZoomState.MIN_SCALE else ZoomState.DOUBLE_TAP_SCALE
                        scope.launch { zoom.animateZoomTo(target, focus) }
                    },
                )
            }
        }
    }

    // Sharp tiles for the visible area, once zooming and scrolling have settled.
    LaunchedEffect(listState, zoom, source) {
        snapshotFlow {
            listOf(
                zoom.scale, zoom.offsetX, zoom.offsetY, zoom.gestureActive, zoom.viewport,
                listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress,
            )
        }.collectLatest {
            if (!zoom.isZoomed) {
                tiles = emptyMap()
                return@collectLatest
            }
            if (zoom.gestureActive || listState.isScrollInProgress) return@collectLatest
            delay(80)
            val fresh = mutableMapOf<Int, DetailTile>()
            visibleRegions(listState, zoom).forEach { region ->
                val bitmap = source.renderRegion(
                    region.page, region.fullWidth, region.fullHeight,
                    region.x, region.y, region.width, region.height,
                ) ?: return@forEach
                val s = zoom.scale
                fresh[region.page] = DetailTile(region.x / s, region.y / s, region.width / s, region.height / s, bitmap)
                tiles = tiles + (region.page to fresh.getValue(region.page))
            }
            tiles = fresh
        }
    }
}

private data class Region(
    val page: Int, val fullWidth: Int, val fullHeight: Int,
    val x: Int, val y: Int, val width: Int, val height: Int,
)

/** For each page on screen, the part that's visible, in pixels of the page scaled by the zoom. */
private fun visibleRegions(listState: LazyListState, zoom: ZoomState): List<Region> {
    val info = listState.layoutInfo
    val s = zoom.scale
    val w = zoom.viewport.width.toFloat()
    val h = zoom.viewport.height.toFloat()
    val left = max(0f, -zoom.offsetX / s)
    val right = min(w, (w - zoom.offsetX) / s)
    return info.visibleItemsInfo.mapNotNull { item ->
        val drawnTop = (item.offset - info.viewportStartOffset).toFloat()
        val top = max(0f, -zoom.offsetY / s - drawnTop)
        val bottom = min(item.size.toFloat(), (h - zoom.offsetY) / s - drawnTop)
        if (bottom <= top || right <= left) return@mapNotNull null
        val fullWidth = (w * s).roundToInt()
        val fullHeight = (item.size * s).roundToInt()
        val x = (left * s).toInt()
        val y = (top * s).toInt()
        Region(
            page = item.index,
            fullWidth = fullWidth,
            fullHeight = fullHeight,
            x = x,
            y = y,
            width = min(ceil((right - left) * s).toInt(), fullWidth - x),
            height = min(ceil((bottom - top) * s).toInt(), fullHeight - y),
        )
    }
}

@Composable
private fun PageItem(
    index: Int,
    size: PageSize,
    widthPx: Int,
    source: PageSource,
    tile: DetailTile?,
    hits: List<SearchHit>,
    currentHit: SearchHit?,
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
) {
    var bitmap by remember(index, widthPx) { mutableStateOf(source.cached(index, widthPx)) }
    LaunchedEffect(index, widthPx) {
        if (bitmap == null) bitmap = source.render(index, widthPx)
    }
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(size.width / size.height)
            .background(Color.White)
            .pointerInput(index) {
                detectTapGestures(onTap = onTap, onDoubleTap = onDoubleTap)
            },
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
        tile?.let { t ->
            with(density) {
                Image(
                    t.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .offset { IntOffset(t.left.roundToInt(), t.top.roundToInt()) }
                        .size(t.width.toDp(), t.height.toDp()),
                )
            }
        }
        if (hits.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val k = this.size.width / size.width
                for (hit in hits) {
                    val color = if (hit == currentHit) CurrentHighlightColor else HighlightColor
                    for (r in hit.rects) {
                        drawRect(color, Offset(r.left * k, r.top * k), Size((r.right - r.left) * k, (r.bottom - r.top) * k))
                    }
                }
            }
        }
    }
}
