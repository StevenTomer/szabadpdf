package org.szabadpdf.feature.organize

import org.szabadpdf.core.ui.Tip
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Thumbnail grid for rearranging pages. Tap pages to select them; hold and drag to move one.
 * Nothing touches the file until Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(
    viewModel: OrganizeViewModel,
    initialPage: Int,
    onClose: () -> Unit,
    onSaved: (Uri) -> Unit,
    /** Opens the scanner; the scanned pages come back through [OrganizeViewModel.insertDocument]. */
    onScan: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val currentOnSaved by rememberUpdatedState(onSaved)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is OrganizeEvent.Saved -> currentOnSaved(event.uri)
                is OrganizeEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
            }
        }
    }

    val insertPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::insertDocument)
    }
    val copyPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            // Keep access so the copy can be reopened from Recent.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.save(target = uri)
        }
    }
    fun saveCopy() = copyPicker.launch(copyName(state.name))

    val extractPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let(viewModel::extractSelected)
    }
    var splitDialog by rememberSaveable { mutableStateOf(false) }
    var splitSize by rememberSaveable { mutableStateOf(1) }
    val splitFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder ->
        folder?.let { viewModel.split(it, splitSize) }
    }

    fun leave() {
        when {
            state.selected.isNotEmpty() -> viewModel.clearSelection()
            state.dirty -> confirmDiscard = true
            else -> onClose()
        }
    }
    BackHandler(onBack = ::leave)

    val selecting = state.selected.isNotEmpty()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selecting) {
                        Tip("Clear selection") {
                            IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Filled.Close, "Clear selection") }
                        }
                    } else {
                        Tip("Back") {
                            IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        }
                    }
                },
                title = {
                    if (selecting) {
                        Text("${state.selected.size} selected")
                    } else {
                        Column {
                            Text(pageCountLabel(state.pages.size), maxLines = 1)
                            Text(
                                "Tap to select · hold to drag",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    val ready = state.status == OrganizeStatus.Ready
                    Tip("Undo") {
                        IconButton(onClick = viewModel::undo, enabled = ready && state.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                        }
                    }
                    Tip("Redo") {
                        IconButton(onClick = viewModel::redo, enabled = ready && state.canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                        }
                    }
                    AddMenu(
                        enabled = ready,
                        onBlank = viewModel::insertBlank,
                        onFromPdf = { insertPicker.launch(arrayOf("application/pdf")) },
                        onScan = onScan,
                    )
                    if (selecting) {
                        val label = if (state.allSelected) "Unselect all" else "Select all"
                        Tip(label) {
                            IconButton(onClick = viewModel::toggleSelectAll) {
                                Icon(if (state.allSelected) Icons.Filled.Deselect else Icons.Filled.SelectAll, label)
                            }
                        }
                    } else {
                        SaveMenu(
                            enabled = ready && state.dirty,
                            canOverwrite = state.canOverwrite,
                            onSave = { viewModel.save() },
                            onSaveCopy = ::saveCopy,
                        )
                        OrganizeMoreMenu(enabled = ready && state.pages.size > 1, onSplit = { splitDialog = true })
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Nine actions share the width; each keeps a full-size touch area.
                        @Composable
                        fun Action(label: String, icon: ImageVector, iconModifier: Modifier = Modifier, onClick: () -> Unit) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Tip(label) { IconButton(onClick = onClick) { Icon(icon, label, iconModifier) } }
                            }
                        }
                        Action("Rotate left", Icons.AutoMirrored.Filled.RotateLeft) { viewModel.rotateSelected(-90) }
                        Action("Rotate right", Icons.AutoMirrored.Filled.RotateRight) { viewModel.rotateSelected(90) }
                        Action("Flip horizontally", Icons.Filled.Flip) { viewModel.flipSelected(horizontal = true) }
                        Action("Flip vertically", Icons.Filled.Flip, Modifier.rotate(90f)) { viewModel.flipSelected(horizontal = false) }
                        Action("Move up", Icons.Filled.ArrowUpward) { viewModel.moveSelected(up = true) }
                        Action("Move down", Icons.Filled.ArrowDownward) { viewModel.moveSelected(up = false) }
                        Action("Duplicate", Icons.Filled.FileCopy, onClick = viewModel::duplicateSelected)
                        Action("Save selected pages as a new PDF", Icons.Filled.Save) { extractPicker.launch(extractName(state.name)) }
                        Action("Delete", Icons.Filled.Delete, onClick = viewModel::deleteSelected)
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val status = state.status) {
            OrganizeStatus.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is OrganizeStatus.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(status.message, style = MaterialTheme.typography.bodyLarge)
            }
            OrganizeStatus.Ready, OrganizeStatus.Saving -> PageGrid(
                state = state,
                viewModel = viewModel,
                initialPage = initialPage,
                contentPadding = padding,
            )
        }
    }

    if (state.status == OrganizeStatus.Saving) SavingDialog()

    if (splitDialog) {
        SplitDialog(
            pageCount = state.pages.size,
            onSplit = { size ->
                splitDialog = false
                splitSize = size
                splitFolder.launch(null)
            },
            onDismiss = { splitDialog = false },
        )
    }

    state.unlock?.let { request ->
        UnlockDialog(request, onUnlock = viewModel::unlock, onSkip = { viewModel.unlock(null) })
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Save your changes?") },
            text = { Text("Your page changes haven't been saved.") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (state.canOverwrite) {
                        TextButton(onClick = {
                            confirmDiscard = false
                            viewModel.save()
                        }) { Text("Save (overwrite)") }
                    }
                    TextButton(onClick = {
                        confirmDiscard = false
                        saveCopy()
                    }) { Text("Save as new PDF…") }
                    TextButton(onClick = {
                        confirmDiscard = false
                        onClose()
                    }) { Text("Discard changes") }
                    TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
                }
            },
        )
    }
}

@Composable
private fun PageGrid(
    state: OrganizeState,
    viewModel: OrganizeViewModel,
    initialPage: Int,
    contentPadding: PaddingValues,
) {
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0))
    val drag = remember(grid) { GridDragState(grid, viewModel::move) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Scroll while a page is held near the top or bottom edge.
    val dragging = drag.draggingKey != null
    // Bring just-inserted pages into view. (Coming back from the scanner, the grid starts
    // where it first opened, so this is what shows the new pages.)
    LaunchedEffect(state.focus) {
        val target = state.focus ?: return@LaunchedEffect
        grid.animateScrollToItem(target.coerceIn(0, (state.pages.size - 1).coerceAtLeast(0)))
        viewModel.focusShown()
    }

    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        val edge = with(density) { 72.dp.toPx() }
        val speed = with(density) { 14.dp.toPx() }
        while (isActive) {
            withFrameNanos { }
            val amount = drag.autoScrollSpeed(edge, speed)
            if (amount != 0f) {
                grid.scrollBy(amount)
                drag.moveIfOverAnother()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(OrganizeViewModel.THUMB_SIZES[state.thumbSize].dp),
        state = grid,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 88.dp, // clear of the size buttons
        ),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .dragToReorder(
                state = drag,
                onTap = { item -> (item.key as? Long)?.let(viewModel::toggleSelected) },
                onDragStart = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.beginDrag()
                },
                onDragEnd = viewModel::endDrag,
            ),
    ) {
        itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
            val isDragged = drag.draggingKey == page.id
            val cellModifier = if (isDragged) {
                Modifier
                    .zIndex(1f)
                    .graphicsLayer {
                        val d = drag.displacement
                        translationX = d.x
                        translationY = d.y
                        scaleX = 1.06f
                        scaleY = 1.06f
                    }
            } else {
                Modifier.animateItem()
            }
            PageCell(
                page = page,
                number = index + 1,
                selected = page.id in state.selected,
                lifted = isDragged,
                viewModel = viewModel,
                modifier = cellModifier,
            )
        }
    }
        ThumbnailSizeControl(
            canShrink = state.thumbSize > 0,
            canGrow = state.thumbSize < OrganizeViewModel.THUMB_SIZES.lastIndex,
            onZoom = viewModel::zoomThumbnails,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = contentPadding.calculateBottomPadding())
                .padding(16.dp),
        )
    }
}

/** Smaller / bigger thumbnails. */
@Composable
private fun ThumbnailSizeControl(canShrink: Boolean, canGrow: Boolean, onZoom: (Int) -> Unit, modifier: Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tip("Smaller thumbnails") {
                IconButton(onClick = { onZoom(-1) }, enabled = canShrink) { Icon(Icons.Filled.ZoomOut, "Smaller thumbnails") }
            }
            Tip("Bigger thumbnails") {
                IconButton(onClick = { onZoom(1) }, enabled = canGrow) { Icon(Icons.Filled.ZoomIn, "Bigger thumbnails") }
            }
        }
    }
}

@Composable
private fun PageCell(
    page: EditPage,
    number: Int,
    selected: Boolean,
    lifted: Boolean,
    viewModel: OrganizeViewModel,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    Column(modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CELL_ASPECT),
            contentAlignment = Alignment.Center,
        ) {
            // Fit the page as it will look once turned, then draw it unturned and rotate.
            val shown = page.displaySize
            val scale = min(constraints.maxWidth / shown.width, constraints.maxHeight / shown.height)
            val widthPx = page.size.width * scale
            val heightPx = page.size.height * scale
            val renderWidth = widthPx.roundToInt().coerceAtLeast(1)
            val angle by animateFloatAsState(page.rotation.toFloat(), label = "rotation")

            val bitmap by produceState(viewModel.cachedThumbnail(page, renderWidth), page.source, page.index, renderWidth) {
                viewModel.thumbnail(page, renderWidth)?.let { value = it }
            }
            val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Box(
                Modifier
                    .requiredSize(with(density) { widthPx.toDp() }, with(density) { heightPx.toDp() })
                    .graphicsLayer {
                        rotationZ = angle
                        // Scaling applies before the turn, so these mirror the page as it is unturned.
                        scaleX = if (page.flipH) -1f else 1f
                        scaleY = if (page.flipV) -1f else 1f
                    }
                    .shadow(if (lifted) 10.dp else 1.dp)
                    .background(Color.White)
                    .border(if (selected) 3.dp else 1.dp, outline),
            ) {
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize()) }
                if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        Text(
            "$number",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun AddMenu(enabled: Boolean, onBlank: () -> Unit, onFromPdf: () -> Unit, onScan: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tip("Add pages") {
            IconButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Filled.Add, "Add pages") }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Blank page") }, onClick = {
                open = false
                onBlank()
            })
            DropdownMenuItem(text = { Text("Pages from another PDF…") }, onClick = {
                open = false
                onFromPdf()
            })
            if (onScan != null) {
                DropdownMenuItem(text = { Text("Scan pages…") }, onClick = {
                    open = false
                    onScan()
                })
            }
        }
    }
}

@Composable
private fun SaveMenu(enabled: Boolean, canOverwrite: Boolean, onSave: () -> Unit, onSaveCopy: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tip("Save") {
            IconButton(
                onClick = { if (canOverwrite) open = true else onSaveCopy() },
                enabled = enabled,
            ) { Icon(Icons.Filled.Save, "Save") }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Save") }, onClick = {
                open = false
                onSave()
            })
            DropdownMenuItem(text = { Text("Save as copy…") }, onClick = {
                open = false
                onSaveCopy()
            })
        }
    }
}

@Composable
private fun SavingDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Row(
                Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text("Saving…")
            }
        }
    }
}

private const val CELL_ASPECT = 0.8f

private fun pageCountLabel(count: Int) = if (count == 1) "1 page" else "$count pages"

private fun copyName(name: String): String {
    val base = name.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Document" }
    return "$base (edited).pdf"
}

@Composable
private fun OrganizeMoreMenu(enabled: Boolean, onSplit: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tip("More options") {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More options") }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Split into files…") }, enabled = enabled, onClick = {
                open = false
                onSplit()
            })
        }
    }
}

/** How many pages go in each file when splitting; then a folder is picked. */
@Composable
private fun SplitDialog(pageCount: Int, onSplit: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("1") }
    val size = text.toIntOrNull()?.takeIf { it in 1 until pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Split into files") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    label = { Text("Pages in each file") },
                    singleLine = true,
                    isError = size == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                )
                Text(
                    if (size == null) "Between 1 and ${pageCount - 1}"
                    else "Makes ${(pageCount + size - 1) / size} files. Next, pick the folder to put them in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { size?.let(onSplit) }, enabled = size != null) { Text("Choose folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun extractName(name: String): String {
    val base = name.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Document" }
    return "$base (pages).pdf"
}
