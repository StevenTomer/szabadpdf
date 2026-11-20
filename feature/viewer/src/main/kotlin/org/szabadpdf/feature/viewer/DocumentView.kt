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
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.szabadpdf.core.pdf.FormField
import org.szabadpdf.core.pdf.InkStyle
import org.szabadpdf.core.pdf.PageSize
import org.szabadpdf.core.pdf.PdfPoint
import org.szabadpdf.core.pdf.ShapeKind
import org.szabadpdf.core.pdf.TextSelection
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A sharp, screen-resolution render of the visible part of one page, in page-item pixels. */
private class DetailTile(
    val left: Float, val top: Float, val width: Float, val height: Float, val bitmap: Bitmap, val version: Long,
)

/** What happens on a page, in page points. Interpretation (which tool, what to select) is the caller's. */
internal interface PageActions {
    fun onTap(page: Int, point: PdfPoint)
    fun onLongPress(page: Int, point: PdfPoint)
    /** The finger moved after a long press. */
    fun onLongPressDrag(page: Int, point: PdfPoint)
    fun onSelectionHandles(page: Int, start: PdfPoint, end: PdfPoint)
    fun onStroke(page: Int, points: List<PdfPoint>, pressure: Float?)
    fun onShape(page: Int, start: PdfPoint, end: PdfPoint)
    fun onErase(page: Int, points: List<PdfPoint>, radius: Float, gesture: Int)
    fun onTextHighlight(page: Int, start: PdfPoint, end: PdfPoint, finished: Boolean)
    fun onTextHighlightCancelled()
    fun onStylusSeen()
    /** A page came into view (e.g. to load its form fields). */
    fun onPageShown(page: Int) {}
}

/** Everything about annotating that the page list draws or reacts to. */
internal class AnnotationLayer(
    val tool: Tool?,
    val inkStyle: InkStyle?,
    val fingerDraws: Boolean,
    val pending: Map<Int, List<PendingStroke>>,
    val selection: TextSelection?,
    val versions: PageVersions,
    val fields: Map<Int, List<FormField>> = emptyMap(),
    val shape: ShapeKind = ShapeKind.RECTANGLE,
    /** Inverted page colours. */
    val night: Boolean = false,
    /** The selection page by page, when it runs over several. */
    val selectionParts: List<TextSelection> = emptyList(),
)

internal val PageGap = 8.dp
private val HighlightColor = Color(0x66FFD54F)
private val CurrentHighlightColor = Color(0x88FF7043)
private val SelectionColor = Color(0x553D8BFD)

/** Inverts colours (white paper becomes black, black text white). */
internal val NightFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(
    androidx.compose.ui.graphics.ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)
private val FieldColor = Color(0x223D6BFD)
private val HandleColor = Color(0xFF3D8BFD)
private val HandleRadius = 7.dp
private val EraserRadius = 12.dp
private var gestureCounter = 0

/**
 * The pages, either one at a time ([paged]: swipe sideways to turn) or as a continuous
 * vertical list, with pinch/double-tap zoom.
 *
 * Pages are rendered once at screen size. When zoomed in and still, the visible part of each
 * page is re-rendered at full resolution on top, so text stays crisp at any zoom level without
 * allocating huge bitmaps.
 */
@Composable
internal fun DocumentView(
    pageSizes: List<PageSize>,
    source: PageSource,
    listState: LazyListState,
    pagerState: PagerState,
    paged: Boolean,
    zoom: ZoomState,
    contentPadding: PaddingValues,
    searchHits: List<SearchHit>,
    currentHit: SearchHit?,
    layer: AnnotationLayer,
    actions: PageActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var tiles by remember { mutableStateOf<Map<Int, DetailTile>>(emptyMap()) }
    val hitsByPage = remember(searchHits) { searchHits.groupBy { it.page } }
    val isPaged by rememberUpdatedState(paged)

    // Each page starts unzoomed; so does switching between modes.
    LaunchedEffect(paged, pagerState.settledPage) {
        if (paged) zoom.reset()
    }

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
            .background(if (layer.night) Color(0xFF101010) else MaterialTheme.colorScheme.surfaceContainerHighest)
            .clipToBounds()
            .onSizeChanged { zoom.viewport = it }
            // Two fingers: pinch-zoom and pan. Runs before the list sees the events.
            .pointerInput(zoom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2 && !zoom.stylusDrawing) {
                            zoom.gestureActive = true
                            zoom.zoomBy(event.calculateZoom(), event.calculateCentroid(useCurrent = true))
                            val leftover = zoom.panBy(event.calculatePan())
                            if (leftover != 0f && !isPaged) listState.dispatchRawDelta(-leftover / zoom.scale)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    zoom.gestureActive = false
                }
            }
            // One finger: the list handles vertical scrolling (or the pager turns pages), this
            // pans the zoomed view: sideways in the list, any direction in page mode.
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
                        if (pressed.size == 1 && !zoom.gestureActive && !zoom.panLocked) {
                            val delta = pressed[0].positionChangeIgnoreConsumed()
                            travelled += delta
                            if (!dragging && travelled.getDistance() > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                if (zoom.isZoomed) zoom.panBy(if (isPaged) delta else Offset(delta.x, 0f))
                                pressed[0].consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .nestedScroll(overscrollToPan),
    ) {
        val itemWidthPx = constraints.maxWidth
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()

        if (paged) {
            HorizontalPager(
                state = pagerState,
                // While zoomed in, one finger pans the page instead of turning it.
                userScrollEnabled = !zoom.isZoomed,
                beyondViewportPageCount = 1,
                key = { it },
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                val size = pageSizes[index]
                val box = pageBox(size, viewportW, viewportH)
                val current = index == pagerState.currentPage
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (current) {
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = zoom.scale
                                scaleY = zoom.scale
                                translationX = zoom.offsetX
                                translationY = zoom.offsetY
                            }
                        },
                ) {
                    val density = LocalDensity.current
                    PageItem(
                        index = index,
                        size = size,
                        widthPx = box.width.roundToInt(),
                        source = source,
                        version = layer.versions.of(index),
                        tile = tiles[index]?.takeIf { current },
                        hits = hitsByPage[index].orEmpty(),
                        currentHit = currentHit?.takeIf { it.page == index },
                        layer = layer,
                        actions = actions,
                        zoom = zoom,
                        modifier = with(density) {
                            Modifier
                                .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                                .size(box.width.toDp(), box.height.toDp())
                        },
                        onDoubleTap = { local ->
                            val focus = Offset(
                                (box.left + local.x) * zoom.scale + zoom.offsetX,
                                (box.top + local.y) * zoom.scale + zoom.offsetY,
                            )
                            val target = if (zoom.isZoomed) ZoomState.MIN_SCALE else ZoomState.DOUBLE_TAP_SCALE
                            scope.launch { zoom.animateZoomTo(target, focus) }
                        },
                    )
                }
            }
        } else LazyColumn(
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
                    version = layer.versions.of(index),
                    tile = tiles[index],
                    hits = hitsByPage[index].orEmpty(),
                    currentHit = currentHit?.takeIf { it.page == index },
                    layer = layer,
                    actions = actions,
                    zoom = zoom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(size.width / size.height),
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
    val versions by rememberUpdatedState(layer.versions)
    LaunchedEffect(listState, pagerState, zoom, source, paged) {
        snapshotFlow {
            listOf(
                versions,
                pagerState.currentPage, pagerState.isScrollInProgress,
                zoom.scale, zoom.offsetX, zoom.offsetY, zoom.gestureActive, zoom.viewport,
                listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress,
            )
        }.collectLatest {
            if (!zoom.isZoomed) {
                tiles = emptyMap()
                return@collectLatest
            }
            if (zoom.gestureActive || listState.isScrollInProgress || pagerState.isScrollInProgress) return@collectLatest
            delay(80)
            val fresh = mutableMapOf<Int, DetailTile>()
            val drawnVersions = versions
            val regions = if (paged) {
                val page = pagerState.currentPage
                val box = pageBox(pageSizes[page], zoom.viewport.width.toFloat(), zoom.viewport.height.toFloat())
                listOfNotNull(regionOf(page, box.left, box.top, box.width, box.height, zoom))
            } else {
                visibleRegions(listState, zoom)
            }
            regions.forEach { region ->
                val bitmap = source.renderRegion(
                    region.page, region.fullWidth, region.fullHeight,
                    region.x, region.y, region.width, region.height,
                ) ?: return@forEach
                val s = zoom.scale
                fresh[region.page] = DetailTile(
                    region.x / s, region.y / s, region.width / s, region.height / s, bitmap, drawnVersions.of(region.page),
                )
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

/** Where a page sits, unzoomed, when fitted whole into the viewport (page mode). */
private class PageBox(val left: Float, val top: Float, val width: Float, val height: Float)

private fun pageBox(size: PageSize, viewportW: Float, viewportH: Float): PageBox {
    val fit = min(viewportW / size.width, viewportH / size.height)
    val w = size.width * fit
    val h = size.height * fit
    return PageBox((viewportW - w) / 2, (viewportH - h) / 2, w, h)
}

/** For each page on screen in the list, the part that's visible. */
private fun visibleRegions(listState: LazyListState, zoom: ZoomState): List<Region> {
    val info = listState.layoutInfo
    return info.visibleItemsInfo.mapNotNull { item ->
        val drawnTop = (item.offset - info.viewportStartOffset).toFloat()
        regionOf(item.index, 0f, drawnTop, zoom.viewport.width.toFloat(), item.size.toFloat(), zoom)
    }
}

/**
 * The visible part of a page drawn (before zooming) at [left], [top] with size [w] × [h], in
 * pixels of the page scaled by the zoom; null if none of it is on screen.
 */
private fun regionOf(page: Int, left: Float, top: Float, w: Float, h: Float, zoom: ZoomState): Region? {
    val s = zoom.scale
    val viewW = zoom.viewport.width.toFloat()
    val viewH = zoom.viewport.height.toFloat()
    val x0 = max(0f, -zoom.offsetX / s - left)
    val x1 = min(w, (viewW - zoom.offsetX) / s - left)
    val y0 = max(0f, -zoom.offsetY / s - top)
    val y1 = min(h, (viewH - zoom.offsetY) / s - top)
    if (x1 <= x0 || y1 <= y0) return null
    val fullWidth = (w * s).roundToInt()
    val fullHeight = (h * s).roundToInt()
    val x = (x0 * s).toInt()
    val y = (y0 * s).toInt()
    return Region(
        page = page,
        fullWidth = fullWidth,
        fullHeight = fullHeight,
        x = x,
        y = y,
        width = min(ceil((x1 - x0) * s).toInt(), fullWidth - x),
        height = min(ceil((y1 - y0) * s).toInt(), fullHeight - y),
    )
}

@Composable
private fun PageItem(
    index: Int,
    size: PageSize,
    widthPx: Int,
    source: PageSource,
    version: Long,
    tile: DetailTile?,
    hits: List<SearchHit>,
    currentHit: SearchHit?,
    layer: AnnotationLayer,
    actions: PageActions,
    zoom: ZoomState,
    modifier: Modifier,
    onDoubleTap: (Offset) -> Unit,
) {
    // After an edit the old picture stays up until the new one is ready.
    var bitmap by remember(index, widthPx) { mutableStateOf(source.cached(index, widthPx, version)) }
    LaunchedEffect(index, widthPx, version) {
        val fresh = source.cached(index, widthPx, version) ?: source.render(index, widthPx, version)
        if (fresh != null) bitmap = fresh
    }
    val density = LocalDensity.current
    val ptPerPx = size.width / widthPx
    fun Offset.toPt() = PdfPoint(x * ptPerPx, y * ptPerPx)

    val currentLayer by rememberUpdatedState(layer)
    val currentActions by rememberUpdatedState(actions)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val liveStroke = remember { mutableStateListOf<Offset>() }
    /** The shape being dragged out, from its first corner to the finger. */
    var liveShape by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    var eraserAt by remember { mutableStateOf<Offset?>(null) }
    val selection = layer.selection?.takeIf { it.page == index }
    val parts = layer.selectionParts.ifEmpty { listOfNotNull(layer.selection) }
    val selectedHere = parts.filter { it.page == index }
    // Handles only for a one-page selection; across pages, long-press again to change it.
    val handles = parts.size == 1 && selection != null
    LaunchedEffect(index) { actions.onPageShown(index) }
    val fields = layer.fields[index].orEmpty().filter { it.fillable }

    Box(
        modifier
            .background(if (layer.night) Color.Black else Color.White)
            // Drawing tools. Runs before the list, so a drawing finger doesn't also scroll.
            // Settings are read per stroke, not used as keys: changing them mustn't cut a stroke short.
            .pointerInput(index) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val tool = currentLayer.tool
                    if (tool == null || !tool.draws) return@awaitEachGesture
                    val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    if ((!stylus && !currentLayer.fingerDraws) || down.isConsumed) return@awaitEachGesture
                    if (stylus) currentActions.onStylusSeen()
                    val erasing = tool == Tool.ERASER || down.type == PointerType.Eraser
                    val eraserRadius = EraserRadius.toPx() / zoom.scale
                    val gesture = ++gestureCounter
                    down.consume()
                    zoom.panLocked = true
                    zoom.stylusDrawing = stylus
                    val path = mutableListOf(down.position)
                    var pressureSum = down.pressure
                    var sent = 0
                    fun flushEraser() {
                        if (path.size - sent < 1) return
                        val from = (sent - 1).coerceAtLeast(0)
                        currentActions.onErase(index, path.subList(from, path.size).map { it.toPt() }, eraserRadius * ptPerPx, gesture)
                        sent = path.size
                    }
                    liveStroke.clear()
                    when {
                        erasing -> eraserAt = down.position
                        tool == Tool.SHAPE -> liveShape = down.position to down.position
                        tool != Tool.TEXT_HIGHLIGHT -> liveStroke += down.position
                    }
                    var cancelled = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (!stylus && event.changes.count { it.pressed } > 1) {
                                cancelled = true
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val points = change.historical.map { it.position } + change.position
                            // With a pen, the palm resting on the screen shouldn't scroll either.
                            if (stylus) event.changes.forEach { it.consume() } else change.consume()
                            path += points
                            pressureSum += change.pressure * points.size
                            when {
                                tool == Tool.SHAPE -> liveShape = path.first() to change.position
                                erasing -> {
                                    eraserAt = change.position
                                    if (path.size - sent >= 4) flushEraser()
                                }
                                tool == Tool.TEXT_HIGHLIGHT ->
                                    currentActions.onTextHighlight(index, path.first().toPt(), change.position.toPt(), false)
                                else -> liveStroke += points
                            }
                            if (!change.pressed) break
                        }
                    } catch (e: CancellationException) {
                        liveStroke.clear()
                        throw e
                    } finally {
                        zoom.panLocked = false
                        zoom.stylusDrawing = false
                        eraserAt = null
                        liveShape = null
                    }
                    when {
                        erasing -> if (!cancelled) flushEraser()
                        tool == Tool.TEXT_HIGHLIGHT ->
                            if (cancelled) currentActions.onTextHighlightCancelled()
                            else currentActions.onTextHighlight(index, path.first().toPt(), path.last().toPt(), true)
                        tool == Tool.SHAPE -> if (!cancelled && (path.last() - path.first()).getDistance() > 4f) {
                            currentActions.onShape(index, path.first().toPt(), path.last().toPt())
                        }
                        !cancelled -> currentActions.onStroke(index, path.map { it.toPt() }, if (stylus) pressureSum / path.size else null)
                    }
                    liveStroke.clear()
                }
            }
            // Taps, double taps, long-press text selection and dragging selection handles.
            .pointerInput(index) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val handleReach = HandleRadius.toPx() * 2.5f / zoom.scale
                    val sel = currentLayer.selection?.takeIf { it.page == index && currentLayer.selectionParts.size <= 1 }
                    val handle = sel?.let { handlePositions(it, 1f / ptPerPx, HandleRadius.toPx() / zoom.scale) }
                        ?.let { (start, end) ->
                            when {
                                (down.position - start).getDistance() < handleReach -> true
                                (down.position - end).getDistance() < handleReach -> false
                                else -> null
                            }
                        }
                    if (sel != null && handle != null) {
                        // Move whichever end was grabbed, keeping the finger's offset from it.
                        val anchor = if (handle) sel.start else sel.end
                        val grab = Offset(anchor.x / ptPerPx, anchor.y / ptPerPx) - down.position
                        down.consume()
                        zoom.panLocked = true
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                if (change.positionChanged()) {
                                    val p = (change.position + grab).toPt()
                                    if (handle) currentActions.onSelectionHandles(index, p, sel.end)
                                    else currentActions.onSelectionHandles(index, sel.start, p)
                                }
                                change.consume()
                            }
                        } finally {
                            zoom.panLocked = false
                        }
                        return@awaitEachGesture
                    }

                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress != null) {
                        currentActions.onLongPress(index, longPress.position.toPt())
                        zoom.panLocked = true
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                if (change.positionChanged()) currentActions.onLongPressDrag(index, change.position.toPt())
                                change.consume()
                            }
                        } finally {
                            zoom.panLocked = false
                        }
                        return@awaitEachGesture
                    }

                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (up == null || !up.changedToUp() || up.isConsumed) return@awaitEachGesture
                    val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                    if (second == null) {
                        currentActions.onTap(index, up.position.toPt())
                    } else if (waitForUpOrCancellation() != null) {
                        currentDoubleTap(second.position)
                    }
                }
            },
    ) {
        val colorFilter = if (layer.night) NightFilter else null
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                colorFilter = colorFilter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        tile?.takeIf { it.version == version }?.let { t ->
            with(density) {
                Image(
                    t.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    colorFilter = colorFilter,
                    modifier = Modifier
                        .offset { IntOffset(t.left.roundToInt(), t.top.roundToInt()) }
                        .size(t.width.toDp(), t.height.toDp()),
                )
            }
        }
        val pending = layer.pending[index].orEmpty()
        Canvas(Modifier.fillMaxSize()) {
            val k = this.size.width / size.width // pixels per point
            for (hit in hits) {
                val color = if (hit == currentHit) CurrentHighlightColor else HighlightColor
                for (r in hit.rects) {
                    drawRect(color, Offset(r.left * k, r.top * k), Size((r.right - r.left) * k, (r.bottom - r.top) * k))
                }
            }
            for (field in fields) {
                val r = field.bounds
                drawRect(FieldColor, Offset(r.left * k, r.top * k), Size((r.right - r.left) * k, (r.bottom - r.top) * k))
            }
            for (stroke in pending) {
                drawInk(stroke.points.map { Offset(it.x * k, it.y * k) }, stroke.style, k)
            }
            val style = layer.inkStyle
            if (style != null && liveStroke.isNotEmpty()) drawInk(liveStroke, style, k)
            val shape = liveShape
            if (style != null && shape != null) drawShapePreview(layer.shape, shape.first, shape.second, style, k)
            eraserAt?.let {
                val r = EraserRadius.toPx() / zoom.scale
                drawCircle(Color(0x33000000), r, it)
                drawCircle(Color(0x99000000), r, it, style = Stroke(1.dp.toPx() / zoom.scale))
            }
            for (part in selectedHere) {
                for (r in part.rects) {
                    drawRect(SelectionColor, Offset(r.left * k, r.top * k), Size((r.right - r.left) * k, (r.bottom - r.top) * k))
                }
            }
            if (selection != null && handles) {
                val radius = HandleRadius.toPx() / zoom.scale
                val (start, end) = handlePositions(selection, k, radius)
                drawCircle(HandleColor, radius, start)
                drawCircle(HandleColor, radius, end)
            }
        }
    }
}

/** Where the selection's two drag handles sit: just below its first and last character. */
private fun handlePositions(selection: TextSelection, pxPerPt: Float, radius: Float): Pair<Offset, Offset> {
    val first = selection.rects.first()
    val last = selection.rects.last()
    return Offset(first.left * pxPerPt, first.bottom * pxPerPt + radius) to
        Offset(last.right * pxPerPt, last.bottom * pxPerPt + radius)
}

private fun DrawScope.drawShapePreview(kind: ShapeKind, a: Offset, b: Offset, style: InkStyle, pxPerPt: Float) {
    val color = Color(0xFF000000.toInt() or style.color).copy(alpha = style.opacity)
    val stroke = Stroke(style.width * pxPerPt, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
    val size = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
    when (kind) {
        ShapeKind.RECTANGLE -> drawRect(color, topLeft, size, style = stroke)
        ShapeKind.ELLIPSE -> drawOval(color, topLeft, size, style = stroke)
        ShapeKind.LINE, ShapeKind.ARROW -> {
            drawLine(color, a, b, stroke.width, StrokeCap.Round)
            if (kind == ShapeKind.ARROW) {
                val angle = kotlin.math.atan2(b.y - a.y, b.x - a.x)
                val head = stroke.width * 4 + 8f
                for (side in listOf(-0.5f, 0.5f)) {
                    val t = angle + kotlin.math.PI.toFloat() + side
                    drawLine(color, b, Offset(b.x + head * kotlin.math.cos(t), b.y + head * kotlin.math.sin(t)), stroke.width, StrokeCap.Round)
                }
            }
        }
    }
}

private fun DrawScope.drawInk(points: List<Offset>, style: InkStyle, pxPerPt: Float) {
    val color = Color(0xFF000000.toInt() or style.color).copy(alpha = style.opacity)
    val width = style.width * pxPerPt
    if (points.size == 1) {
        drawCircle(color, width / 2f, points[0])
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
