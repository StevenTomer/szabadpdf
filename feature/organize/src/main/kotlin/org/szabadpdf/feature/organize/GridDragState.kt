package org.szabadpdf.feature.organize

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange

/**
 * Long-press-and-drag reordering for a [LazyGridState]. Positions are in the grid's own
 * coordinates; item offsets from the layout info are relative to the viewport, so they're
 * shifted by [androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo.viewportStartOffset].
 */
@Stable
internal class GridDragState(
    private val grid: LazyGridState,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** Where the dragged item started, plus how far the finger has moved since. */
    private var startOffset = Offset.Zero
    private var delta by mutableStateOf(Offset.Zero)
    private var pointer = Offset.Zero

    private val dragged: LazyGridItemInfo?
        get() = draggingKey?.let { key -> grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } }

    /** How far the dragged item is drawn from where the grid laid it out. */
    val displacement: Offset
        get() = dragged?.let { startOffset + delta - it.offset.toOffset() } ?: Offset.Zero

    fun itemAt(position: Offset): LazyGridItemInfo? {
        val shift = grid.layoutInfo.viewportStartOffset
        return grid.layoutInfo.visibleItemsInfo.firstOrNull {
            position.x >= it.offset.x && position.x < it.offset.x + it.size.width &&
                position.y >= it.offset.y - shift && position.y < it.offset.y - shift + it.size.height
        }
    }

    fun start(position: Offset): Boolean {
        val item = itemAt(position) ?: return false
        draggingKey = item.key
        startOffset = item.offset.toOffset()
        delta = Offset.Zero
        pointer = position
        return true
    }

    fun dragBy(amount: Offset) {
        delta += amount
        pointer += amount
        moveIfOverAnother()
    }

    fun end() {
        draggingKey = null
        delta = Offset.Zero
    }

    /** Swaps places with whichever item the dragged one's centre is over. */
    fun moveIfOverAnother() {
        val item = dragged ?: return
        val shift = grid.layoutInfo.viewportStartOffset
        val center = startOffset + delta + Offset(item.size.width / 2f, item.size.height / 2f - shift)
        val target = itemAt(center)?.takeIf { it.key != item.key } ?: return
        val first = grid.firstVisibleItemIndex
        val firstOffset = grid.firstVisibleItemScrollOffset
        onMove(item.index, target.index)
        // The grid keeps the first visible item in place, which would drag the view along
        // when that item is the one moving.
        if (item.index == first || target.index == first) grid.requestScrollToItem(first, firstOffset)
    }

    /** Pixels to scroll this frame when the finger is near the top or bottom edge. */
    fun autoScrollSpeed(edge: Float, maxSpeed: Float): Float {
        if (draggingKey == null) return 0f
        val height = grid.layoutInfo.viewportSize.height
        return when {
            pointer.y < edge -> -maxSpeed * ((edge - pointer.y) / edge).coerceAtMost(1f)
            pointer.y > height - edge -> maxSpeed * ((pointer.y - (height - edge)) / edge).coerceAtMost(1f)
            else -> 0f
        }
    }

    private fun androidx.compose.ui.unit.IntOffset.toOffset() = Offset(x.toFloat(), y.toFloat())
}

/**
 * Taps go to [onTap]; a long press starts dragging the item under the finger. While
 * dragging, events are consumed before the grid sees them, so it doesn't scroll as well.
 */
internal fun Modifier.dragToReorder(
    state: GridDragState,
    onTap: (LazyGridItemInfo) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id)
        if (longPress == null) {
            val up = currentEvent.changes.firstOrNull { it.id == down.id }
            if (up != null && up.changedToUp() && !up.isConsumed) state.itemAt(up.position)?.let(onTap)
            return@awaitEachGesture
        }
        if (!state.start(longPress.position)) return@awaitEachGesture
        onDragStart()
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    change.consume()
                    break
                }
                state.dragBy(change.positionChange())
                change.consume()
            }
        } finally {
            state.end()
            onDragEnd()
        }
    }
}
