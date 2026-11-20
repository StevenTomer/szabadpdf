package org.szabadpdf.feature.organize

import org.szabadpdf.core.ui.Tip
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Pick PDFs, put them in order, save them as one new file. [onSaved] gets the new file;
 * [onClose] leaves without merging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(viewModel: MergeViewModel, onSaved: (Uri) -> Unit, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val currentOnSaved by rememberUpdatedState(onSaved)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is MergeEvent.Saved -> currentOnSaved(event.uri)
                is MergeEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.add(uris)
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.save(uri)
        }
    }

    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { Tip("Back") { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } } },
                title = {
                    Column {
                        Text("Merge PDFs")
                        if (state.files.isNotEmpty()) {
                            Text(
                                "${state.files.size} files · ${state.pageCount} pages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { picker.launch(arrayOf("application/pdf")) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, null)
                        Text("Add files", Modifier.padding(start = 8.dp))
                    }
                    Button(
                        onClick = { saveAs.launch(mergedName(state.files.firstOrNull()?.name)) },
                        enabled = state.files.size >= 2 && state.adding == 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Merge") }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.files.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                if (state.adding > 0) {
                    CircularProgressIndicator()
                } else {
                    Text(
                        "Add the PDFs to combine. You can put them in order before merging; the files themselves aren't changed.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
                itemsIndexed(state.files, key = { _, f -> f.id }) { index, file ->
                    ListItem(
                        leadingContent = { Text("${index + 1}", style = MaterialTheme.typography.titleMedium) },
                        headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(if (file.pageCount == 1) "1 page" else "${file.pageCount} pages") },
                        trailingContent = {
                            Row {
                                Tip("Move up") {
                                    IconButton(onClick = { viewModel.move(file.id, -1) }, enabled = index > 0) {
                                        Icon(Icons.Filled.KeyboardArrowUp, "Move up")
                                    }
                                }
                                Tip("Move down") {
                                    IconButton(onClick = { viewModel.move(file.id, 1) }, enabled = index < state.files.size - 1) {
                                        Icon(Icons.Filled.KeyboardArrowDown, "Move down")
                                    }
                                }
                                Tip("Remove") {
                                    IconButton(onClick = { viewModel.remove(file.id) }) { Icon(Icons.Filled.Close, "Remove") }
                                }
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (state.adding > 0) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                }
            }
        }
    }

    state.unlock?.let { request ->
        UnlockDialog(request, onUnlock = viewModel::unlock, onSkip = { viewModel.unlock(null) })
    }

    if (state.saving) {
        Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Merging…")
                }
            }
        }
    }
}

private fun mergedName(first: String?): String {
    val base = first?.removeSuffix(".pdf")?.removeSuffix(".PDF")?.ifBlank { null } ?: "Merged"
    return "$base (merged).pdf"
}
