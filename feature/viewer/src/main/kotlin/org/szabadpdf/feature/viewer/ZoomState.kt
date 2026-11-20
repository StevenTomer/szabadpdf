package org.szabadpdf.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * Zoom and pan applied on top of the scrolling page list.
 *
 * A point drawn at (x, y) inside the list appears on screen at
 * (x * scale + offsetX, y * scale + offsetY). Vertical movement is mostly the list's own
 * scrolling; [offsetY] only covers the slack that zooming creates at the top and bottom.
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** True while fingers are pinching; sharp re-rendering waits until it's over. */
    var gestureActive by mutableStateOf(false)
        internal set

    var viewport by mutableStateOf(IntSize.Zero)
        internal set

    /** Set while a page gesture (drawing, selecting) owns one-finger movement: no sideways panning. */
    internal var panLocked = false

    /** Set while a stylus draws, so a resting palm doesn't start a pinch. */
    internal var stylusDrawing = false

    val isZoomed: Boolean get() = scale > 1.01f

    /** Zooms by [factor] keeping the screen point [focus] fixed. */
    fun zoomBy(factor: Float, focus: Offset) {
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = newScale / scale
        offsetX = focus.x - (focus.x - offsetX) * ratio
        offsetY = focus.y - (focus.y - offsetY) * ratio
        scale = newScale
        clamp()
    }

    /** Pans by [delta] screen pixels; returns the vertical amount that didn't fit and should scroll the list. */
    fun panBy(delta: Offset): Float {
        val wantedY = offsetY + delta.y
        offsetX += delta.x
        offsetY = wantedY
        clamp()
        return wantedY - offsetY
    }

    /** Back to the whole page, unzoomed. */
    fun reset() {
        scale = MIN_SCALE
        offsetX = 0f
        offsetY = 0f
    }

    fun setOffsets(x: Float = offsetX, y: Float = offsetY) {
        offsetX = x
        offsetY = y
        clamp()
    }

    /** Animates to [target] scale around [focus], e.g. for double-tap. */
    suspend fun animateZoomTo(target: Float, focus: Offset) {
        val startScale = scale
        val startX = offsetX
        val startY = offsetY
        val goal = target.coerceIn(MIN_SCALE, MAX_SCALE)
        val progress = Animatable(0f)
        progress.animateTo(1f, tween(250)) {
            val s = startScale + (goal - startScale) * value
            val ratio = s / startScale
            scale = s
            offsetX = focus.x - (focus.x - startX) * ratio
            offsetY = focus.y - (focus.y - startY) * ratio
            clamp()
        }
    }

    /**
     * The page being read: the one under a line 30% of the way down the screen. (Largest
     * visible area misjudges a short landscape page followed by a tall portrait one.)
     */
    fun currentPage(list: LazyListState): Int {
        val info = list.layoutInfo
        val lineY = (viewport.height * 0.3f - offsetY) / scale
        val items = info.visibleItemsInfo
        return items.lastOrNull { (it.offset - info.viewportStartOffset) <= lineY }?.index
            ?: items.firstOrNull()?.index
            ?: list.firstVisibleItemIndex
    }

    private fun clamp() {
        val w = viewport.width.toFloat()
        val h = viewport.height.toFloat()
        offsetX = offsetX.coerceIn(w - w * scale, 0f)
        offsetY = offsetY.coerceIn(h - h * scale, 0f)
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}
