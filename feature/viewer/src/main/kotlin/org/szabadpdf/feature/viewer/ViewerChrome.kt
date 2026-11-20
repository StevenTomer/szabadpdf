package org.szabadpdf.feature.viewer

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
)

internal val TabStripHeight = 44.dp

@Composable
internal fun TabStrip(tabs: TabsUi, onClose: () -> Unit) {
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
                for (tab in tabs.tabs) {
                    val active = tab.id == tabs.active
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
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
                                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Close, "Close tab", Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            IconButton(onClick = tabs.onNew) { Icon(Icons.Filled.Add, "Open another document") }
        }
    }
}

/** The ⋮ menu: contents, organizing, view mode, share, save as, print. */
@Composable
internal fun MoreMenu(
    paged: Boolean,
    canOrganize: Boolean,
    canSave: Boolean,
    onContents: () -> Unit,
    onOrganize: () -> Unit,
    onScanInto: () -> Unit,
    onPaged: (Boolean) -> Unit,
    onShare: () -> Unit,
    onSaveAs: () -> Unit,
    onPrint: () -> Unit,
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
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Contents") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                onClick = pick(onContents),
            )
            if (canOrganize) {
                DropdownMenuItem(
                    text = { Text("Organize pages") },
                    leadingIcon = { Icon(Icons.Filled.GridView, null) },
                    onClick = pick(onOrganize),
                )
                DropdownMenuItem(
                    text = { Text("Scan pages into this document") },
                    leadingIcon = { Icon(Icons.Filled.DocumentScanner, null) },
                    onClick = pick(onScanInto),
                )
            }
            DropdownMenuItem(
                text = { Text("Scroll continuously") },
                leadingIcon = { Icon(Icons.Filled.ViewDay, null) },
                trailingIcon = { Checkbox(checked = !paged, onCheckedChange = null) },
                onClick = pick { onPaged(!paged) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Filled.Share, null) },
                onClick = pick(onShare),
            )
            if (canSave) {
                DropdownMenuItem(
                    text = { Text("Save as…") },
                    leadingIcon = { Icon(Icons.Filled.SaveAs, null) },
                    onClick = pick(onSaveAs),
                )
            }
            if (canSave) {
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
            DropdownMenuItem(
                text = { Text("Print") },
                leadingIcon = { Icon(Icons.Filled.Print, null) },
                onClick = pick(onPrint),
            )
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
