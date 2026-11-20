package org.szabadpdf

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.szabadpdf.core.pdf.LocalFiles
import org.szabadpdf.core.ui.Tip
import java.io.File

/** What's in a folder: its subfolders and PDFs, by name. */
private data class Listing(val folders: List<File>, val pdfs: List<File>)

/**
 * Lists the PDFs in a folder (starting at [start], e.g. the folder of the document being read)
 * to tick and open together, with its subfolders and the main storage places to move around.
 * Needs file access, which it asks for; [onSystemPicker] is the way to cloud drives and other apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenPdfsDialog(
    start: File?,
    onOpen: (List<File>) -> Unit,
    onSystemPicker: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(LocalFiles.hasAccess(context)) }
    // Coming back from the settings screen where access is granted.
    LifecycleResumeEffect(Unit) {
        access = LocalFiles.hasAccess(context)
        onPauseOrDispose {}
    }
    val roots = remember { LocalFiles.roots(context) }
    var dir by remember { mutableStateOf(start?.takeIf { it.isDirectory } ?: roots.first().second) }
    var selected by remember { mutableStateOf(emptySet<File>()) }

    val listing by produceState<Listing?>(null, dir, access) {
        value = null
        if (!access) return@produceState
        value = withContext(Dispatchers.IO) {
            val all = dir.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
            Listing(
                folders = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() },
                pdfs = all.filter { it.isFile && it.name.endsWith(".pdf", ignoreCase = true) }.sortedBy { it.name.lowercase() },
            )
        }
    }

    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        access = granted
    }
    fun askForAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }
                .recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else {
            legacyPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopAppBar(
                    navigationIcon = {
                        Tip("Cancel") { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") } }
                    },
                    title = { Text("Open PDFs") },
                    actions = {
                        TextButton(onClick = onSystemPicker) { Text("Other apps…") }
                    },
                )
                if (!access) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Allow access to your files", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "To list the PDFs in a folder, SzabadPDF needs Android's \"All files access\". " +
                                "It also lets the app keep opening files that other apps hand it, after the app restarts. " +
                                "SzabadPDF only reads and saves the documents you open; nothing leaves your phone.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = ::askForAccess) { Text("Allow access") }
                        TextButton(onClick = onSystemPicker) { Text("Use Android's file picker instead") }
                    }
                    return@Column
                }

                // Where we are, and the main places to jump to.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for ((label, root) in roots) {
                        FilterChip(selected = dir == root, onClick = { dir = root }, label = { Text(label) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val parent = dir.parentFile?.takeIf { it.canRead() }
                    Tip("Up one folder") {
                        IconButton(onClick = { parent?.let { dir = it } }, enabled = parent != null) {
                            Icon(Icons.Filled.ArrowUpward, "Up one folder")
                        }
                    }
                    Text(
                        displayPath(dir, roots),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
                HorizontalDivider()

                Box(Modifier.weight(1f)) {
                    val l = listing
                    when {
                        l == null -> {}
                        l.folders.isEmpty() && l.pdfs.isEmpty() -> Text(
                            "No PDFs or folders here",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(l.folders, key = { "d:" + it.path }) { folder ->
                                ListItem(
                                    headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                                    modifier = Modifier.clickable { dir = folder },
                                )
                            }
                            items(l.pdfs, key = { "f:" + it.path }) { pdf ->
                                val checked = pdf in selected
                                fun toggle() {
                                    selected = if (checked) selected - pdf else selected + pdf
                                }
                                ListItem(
                                    headlineContent = { Text(pdf.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Text(
                                            Formatter.formatShortFileSize(context, pdf.length()) + " · " +
                                                DateUtils.getRelativeTimeSpanString(pdf.lastModified()),
                                        )
                                    },
                                    leadingContent = { Icon(Icons.Filled.PictureAsPdf, null, tint = MaterialTheme.colorScheme.error) },
                                    trailingContent = { Checkbox(checked = checked, onCheckedChange = { toggle() }) },
                                    modifier = Modifier.clickable(onClick = ::toggle),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selected.isEmpty()) "Tick the PDFs to open" else "${selected.size} selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onOpen(selected.sortedBy { it.name.lowercase() }) }, enabled = selected.isNotEmpty()) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

/** [dir] as a short path from the storage place it's in, e.g. "Internal storage › Download › Work". */
private fun displayPath(dir: File, roots: List<Pair<String, File>>): String {
    val main = roots.firstOrNull { it.first == "Internal storage" }?.second
    val (label, base) = when {
        main != null && dir.path.startsWith(main.path) -> "Internal storage" to main
        else -> roots.firstOrNull { dir.path.startsWith(it.second.path) && it.first == "SD card" }?.let { it.first to it.second }
            ?: return dir.path
    }
    val rest = dir.path.removePrefix(base.path).trim('/').split('/').filter { it.isNotEmpty() }
    return (listOf(label) + rest).joinToString(" › ")
}
