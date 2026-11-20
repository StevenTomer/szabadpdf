package org.szabadpdf.feature.viewer

import org.szabadpdf.core.ui.Tip
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.key
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** An open document in the tab strip. */
data class DocumentTab(val id: String, val title: String)

/** The open tabs, which one is showing, and what the strip's buttons do. */
class TabsUi(
    val tabs: List<DocumentTab>,
    val active: String,
    val onSelect: (String) -> Unit,
    /** Closes the active tab (after any unsaved-changes prompt). */
    val onClose: () -> Unit,
    /** Opens another document. */
    val onNew: () -> Unit,
    /** Opens [Uri] (picked with the viewer's Open button) in a tab. */
    val onOpen: ((android.net.Uri) -> Unit)? = null,
    /** Moves the tab [from] one position to [to]. */
    val onMove: ((from: Int, to: Int) -> Unit)? = null,
)

internal val TabStripHeight = 44.dp

@Composable
internal fun TabStrip(tabs: TabsUi, onClose: () -> Unit) {
    val drag = remember { TabDrag() }
    val widths = remember { mutableMapOf<String, Int>() }
    val spacing = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
    val currentTabs by androidx.compose.runtime.rememberUpdatedState(tabs.tabs)
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(TabStripHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((index, tab) in tabs.tabs.withIndex()) key(tab.id) {
                    val active = tab.id == tabs.active
                    val dragged = drag.id == tab.id
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        shadowElevation = if (dragged) 6.dp else 0.dp,
                        modifier = Modifier
                            .onSizeChanged { widths[tab.id] = it.width }
                            .zIndex(if (dragged) 1f else 0f)
                            .graphicsLayer { translationX = if (dragged) drag.offset else 0f }
                            .then(
                                if (tabs.onMove != null && tabs.tabs.size > 1) {
                                    Modifier.pointerInput(tab.id) { reorderTabs(tab.id, drag, widths, spacing, { currentTabs }, tabs.onMove) }
                                } else Modifier,
                            )
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !active, onClickLabel = "Switch to ${tab.title}") { tabs.onSelect(tab.id) },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = if (active) 2.dp else 12.dp)) {
                            Text(
                                tab.title,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(max = 160.dp)
                                    .padding(vertical = 8.dp),
                            )
                            if (active) {
                                Tip("Close tab") {
                                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Close, "Close tab", Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Tip("Open another document") {
                IconButton(onClick = tabs.onNew) { Icon(Icons.Filled.Add, "Open another document") }
            }
        }
    }
}

/** The ⋮ menu: contents, view options, and less common document tools. */
@Composable
internal fun MoreMenu(
    paged: Boolean,
    canSave: Boolean,
    onContents: () -> Unit,
    onPaged: (Boolean) -> Unit,
    night: Boolean,
    onNight: (Boolean) -> Unit,
    thumbnails: Boolean,
    onThumbnails: (Boolean) -> Unit,
    onPassword: () -> Unit,
    onCompress: () -> Unit,
    onRecognize: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun pick(action: () -> Unit) = {
        open = false
        action()
    }
    Box {
        Tip("More options") {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More options") }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Contents") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                onClick = pick(onContents),
            )
            DropdownMenuItem(
                text = { Text("Scroll continuously") },
                leadingIcon = { Icon(Icons.Filled.ViewDay, null) },
                trailingIcon = { Checkbox(checked = !paged, onCheckedChange = null) },
                onClick = pick { onPaged(!paged) },
            )
            DropdownMenuItem(
                text = { Text("Night mode") },
                leadingIcon = { Icon(Icons.Filled.DarkMode, null) },
                trailingIcon = { Checkbox(checked = night, onCheckedChange = null) },
                onClick = pick { onNight(!night) },
            )
            DropdownMenuItem(
                text = { Text("Page thumbnails") },
                leadingIcon = { Icon(Icons.Filled.ViewCarousel, null) },
                trailingIcon = { Checkbox(checked = thumbnails, onCheckedChange = null) },
                onClick = pick { onThumbnails(!thumbnails) },
            )
            if (canSave) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Recognize text (OCR)") },
                    leadingIcon = { Icon(Icons.Filled.TextFields, null) },
                    onClick = pick(onRecognize),
                )
                DropdownMenuItem(
                    text = { Text("Reduce file size…") },
                    leadingIcon = { Icon(Icons.Filled.Compress, null) },
                    onClick = pick(onCompress),
                )
                DropdownMenuItem(
                    text = { Text("Password…") },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                    onClick = pick(onPassword),
                )
            }
        }
    }
}

/** Hands a finished PDF file to Android's print system as it is. */
internal class PdfPrintAdapter(private val file: File) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(file.name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) }
            }
            if (cancellationSignal.isCanceled) callback.onWriteCancelled()
            else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}

/** A strip of page thumbnails; tap one to go there. Follows the [current] page. */
@Composable
internal fun ThumbnailStrip(
    pageSizes: List<org.szabadpdf.core.pdf.PageSize>,
    current: Int,
    source: PageSource,
    versions: PageVersions,
    night: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    androidx.compose.runtime.LaunchedEffect(current) { list.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val widthPx = with(density) { ThumbWidth.roundToPx() }
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.lazy.LazyRow(
            state = list,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(pageSizes.size, key = { it }) { page ->
                val size = pageSizes[page]
                val version = versions.of(page)
                val bitmap by androidx.compose.runtime.produceState(source.cached(page, widthPx, version), page, version) {
                    if (value == null) value = source.render(page, widthPx, version)
                }
                val selected = page == current
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(width = ThumbWidth, height = ThumbWidth * (size.height / size.width).coerceIn(0.5f, 1.6f))
                            .background(if (night) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White)
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            )
                            .clickable(onClickLabel = "Go to page ${page + 1}") { onSelect(page) },
                    ) {
                        bitmap?.let {
                            androidx.compose.foundation.Image(
                                it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                colorFilter = if (night) NightFilter else null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Text(
                        "${page + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal val ThumbWidth = 48.dp

/** One of the document actions along the bottom: an icon with a short label under it. */
private class DocAction(val label: String, val description: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

/** The main things to do with a document, one tap away: open, edit pages, scan, save as, print, share. */
@Composable
internal fun DocumentActions(
    canEdit: Boolean,
    onOpen: (() -> Unit)?,
    onEdit: () -> Unit,
    onScan: () -> Unit,
    onSaveAs: () -> Unit,
    onPrint: () -> Unit,
    onShare: () -> Unit,
) {
    val actions = buildList {
        if (onOpen != null) add(DocAction("Open", "Open a PDF from this folder", Icons.Filled.FolderOpen, onOpen))
        if (canEdit) {
            add(DocAction("Edit", "Edit pages: reorder, rotate, delete, insert…", Icons.Filled.GridView, onEdit))
            add(DocAction("Scan", "Scan pages into this document", Icons.Filled.DocumentScanner, onScan))
            add(DocAction("Save as", "Save as…", Icons.Filled.SaveAs, onSaveAs))
        }
        add(DocAction("Print", "Print", Icons.Filled.Print, onPrint))
        add(DocAction("Share", "Share", Icons.Filled.Share, onShare))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        for (action in actions) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Tip(action.description) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = action.description, onClick = action.onClick)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Icon(action.icon, contentDescription = null)
                        Text(action.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The tab being dragged to a new place, and how far it is from its slot. */
private class TabDrag {
    var id by mutableStateOf<String?>(null)
    var offset by androidx.compose.runtime.mutableFloatStateOf(0f)
}

/**
 * Hold a tab, then drag it sideways: once it's passed the middle of its neighbour, the two
 * swap places.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.reorderTabs(
    id: String,
    drag: TabDrag,
    widths: Map<String, Int>,
    spacing: Float,
    tabs: () -> List<DocumentTab>,
    onMove: (Int, Int) -> Unit,
) {
    detectDragGesturesAfterLongPress(
        onDragStart = {
            drag.id = id
            drag.offset = 0f
        },
        onDragEnd = { drag.id = null },
        onDragCancel = { drag.id = null },
        onDrag = { change, amount ->
            change.consume()
            drag.offset += amount.x
            val list = tabs()
            val index = list.indexOfFirst { it.id == id }
            val next = list.getOrNull(index + 1)
            val previous = list.getOrNull(index - 1)
            if (drag.offset > 0 && next != null) {
                val step = (widths[next.id] ?: return@detectDragGesturesAfterLongPress) + spacing
                if (drag.offset > step / 2) {
                    onMove(index, index + 1)
                    drag.offset -= step
                }
            } else if (drag.offset < 0 && previous != null) {
                val step = (widths[previous.id] ?: return@detectDragGesturesAfterLongPress) + spacing
                if (-drag.offset > step / 2) {
                    onMove(index, index - 1)
                    drag.offset += step
                }
            }
        },
    )
}
