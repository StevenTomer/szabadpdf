package org.szabadpdf.feature.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The scanner: camera (or photo import), then review, then save as PDF. [onSaved] gets the new
 * file; [onClose] is called after the user leaves (unsaved pages are discarded first).
 */
@Composable
fun ScanScreen(viewModel: ScanViewModel, onSaved: (Uri) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var hasCamera by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val currentOnSaved by rememberUpdatedState(onSaved)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ScanEvent.Saved -> {
                    viewModel.discard()
                    currentOnSaved(event.uri)
                }
                is ScanEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.import(uris)
            reviewing = true
        }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.save(uri, displayName(context, uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: "Scan")
        }
    }

    fun leave() {
        if (state.pages.isNotEmpty() || state.processing > 0) confirmDiscard = true
        else {
            viewModel.discard()
            onClose()
        }
    }
    BackHandler {
        if (reviewing && hasCamera) reviewing = false else leave()
    }

    Box(Modifier.fillMaxSize()) {
        when {
            reviewing && (state.pages.isNotEmpty() || state.processing > 0) -> ReviewScreen(
                state = state,
                viewModel = viewModel,
                onAddMore = { if (hasCamera) reviewing = false else gallery.launch(imagesOnly()) },
                onSave = { saveAs.launch(defaultName()) },
            )
            hasCamera -> CameraScreen(viewModel, onReview = { reviewing = true }, onClose = ::leave)
            else -> NoCamera(
                onAllow = { permission.launch(Manifest.permission.CAMERA) },
                onImport = { gallery.launch(imagesOnly()) },
                onClose = ::leave,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
    }

    if (confirmDiscard) {
        val count = state.pages.size
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(if (count == 1) "Discard the scanned page?" else "Discard $count scanned pages?") },
            text = { Text("They haven't been saved as a PDF.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discard()
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }

    if (state.saving) {
        Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Saving…")
                }
            }
        }
    }
}

@Composable
private fun NoCamera(onAllow: () -> Unit, onImport: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Scan documents", style = MaterialTheme.typography.headlineSmall)
        Text(
            "SzabadPDF needs the camera to scan. Photos stay on your device.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAllow) { Text("Allow camera") }
        OutlinedButton(onClick = onImport) { Text("Import photos instead") }
        TextButton(onClick = onClose) { Text("Cancel") }
    }
}

private fun imagesOnly() = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

private fun defaultName(): String =
    "Scan " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date()) + ".pdf"

/** The file name the user chose, as the document's title. */
private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull()
