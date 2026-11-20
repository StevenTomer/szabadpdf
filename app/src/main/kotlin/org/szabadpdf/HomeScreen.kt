package org.szabadpdf

import org.szabadpdf.core.ui.Tip
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A document open in a tab, as listed on the home screen. */
data class OpenTab(val uri: android.net.Uri, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    openTabs: List<OpenTab>,
    recents: List<RecentFile>,
    onOpenPicker: () -> Unit,
    onScan: () -> Unit,
    onMerge: () -> Unit,
    onOpenTab: (android.net.Uri) -> Unit,
    /** Closes a tab; asks first when [hasUnsavedWork] says it has edits that would be lost. */
    onCloseTab: (android.net.Uri) -> Unit,
    hasUnsavedWork: (android.net.Uri) -> Boolean,
    onOpenRecent: (RecentFile) -> Unit,
    onRemoveRecent: (RecentFile) -> Unit,
) {
    var closing by remember { mutableStateOf<OpenTab?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SzabadPDF") },
                actions = {
                    @Composable
                    fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
                        Tip(description) {
                            TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Icon(icon, contentDescription = null, Modifier.size(20.dp))
                                Text(label, Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                    Action("Open", Icons.Filled.FolderOpen, "Open a PDF", onOpenPicker)
                    Action("Scan", Icons.Filled.DocumentScanner, "Scan paper into a new PDF", onScan)
                    Action("Merge", Icons.Filled.MergeType, "Merge PDFs into one", onMerge)
                },
            )
        },
    ) { padding ->
        if (recents.isEmpty() && openTabs.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No documents yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Open a PDF from your phone or a cloud drive, scan a paper document with the camera, " +
                            "or choose SzabadPDF when opening a PDF in another app.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
                if (openTabs.isNotEmpty()) {
                    item {
                        Text(
                            "Open",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(openTabs, key = { "tab:" + it.uri }) { tab ->
                        ListItem(
                            headlineContent = { Text(tab.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
                            trailingContent = {
                                Tip("Close") {
                                    IconButton(onClick = { if (hasUnsavedWork(tab.uri)) closing = tab else onCloseTab(tab.uri) }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Close")
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onOpenTab(tab.uri) },
                        )
                    }
                }
                item {
                    Text(
                        "Recent",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(recents, key = { it.uri.toString() }) { file ->
                    ListItem(
                        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            val time = DateUtils.getRelativeTimeSpanString(file.openedAt)
                            Text("Page ${file.lastPage + 1} · $time")
                        },
                        trailingContent = {
                            Tip("Remove from recent") {
                                IconButton(onClick = { onRemoveRecent(file) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove from recent")
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpenRecent(file) },
                    )
                }
            }
        }
    }

    closing?.let { tab ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text("Close without saving?") },
            text = { Text("${tab.name} has changes that haven't been saved. Closing it discards them.") },
            confirmButton = {
                TextButton(onClick = {
                    closing = null
                    onCloseTab(tab.uri)
                }) { Text("Discard and close") }
            },
            dismissButton = { TextButton(onClick = { closing = null }) { Text("Cancel") } },
        )
    }
}
