package org.szabadpdf.feature.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Save
import androidx.compose.foundation.pager.rememberPagerState
import androidx.core.content.FileProvider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import org.szabadpdf.core.pdf.AnnotationInfo
import org.szabadpdf.core.pdf.AnnotationKind
import org.szabadpdf.core.pdf.FieldKind
import org.szabadpdf.core.pdf.FormField
import org.szabadpdf.core.pdf.MarkupKind
import org.szabadpdf.core.pdf.PdfPoint
import org.szabadpdf.core.pdf.TextSelection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.szabadpdf.core.pdf.LinkTarget
import kotlin.math.max

/**
 * The full viewer: loading/password/error states and, once open, the page list with its
 * top bar, search, table of contents and page indicator.
 */
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    onPageChanged: (page: Int) -> Unit,
    onOrganize: ((currentPage: Int) -> Unit)? = null,
    onSavedAs: (Uri) -> Unit = {},
    tabs: TabsUi? = null,
    /** Scans pages to insert after the given page (through the page organizer). */
    onScanInto: ((currentPage: Int) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        ViewerState.Idle, ViewerState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is ViewerState.Error -> ErrorScreen(s.message, onBack)
        is ViewerState.NeedsPassword -> PasswordDialog(s.wrongPassword, viewModel::submitPassword, onBack)
        is ViewerState.Ready -> key(s.document.uri) {
            DocumentScreen(s.document, viewModel, onBack, onPageChanged, onOrganize, onSavedAs, tabs, onScanInto)
        }
    }
}

/** An annotation tapped with the select tool (or a note tapped while reading). */
private data class AnnotationTap(val page: Int, val info: AnnotationInfo)

/** Where a new note goes, before its text is written. */
private data class NewNote(val page: Int, val point: PdfPoint)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun DocumentScreen(
    info: DocumentInfo,
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onOrganize: ((Int) -> Unit)?,
    onSavedAs: (Uri) -> Unit,
    tabs: TabsUi?,
    onScanInto: ((Int) -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = info.initialPage)
    val pagerState = rememberPagerState(initialPage = info.initialPage) { info.pageSizes.size }
    val paged by viewModel.paged.collectAsState()
    val zoom = remember { ZoomState() }
    val search by viewModel.search.collectAsState()
    val annotate by viewModel.annotate.collectAsState()
    val versions by viewModel.versions.collectAsState()
    val pendingInk by viewModel.pendingInk.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val pageCount = info.pageSizes.size

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var showOutline by rememberSaveable { mutableStateOf(false) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }
    var showStyle by rememberSaveable { mutableStateOf(false) }
    var showProtect by rememberSaveable { mutableStateOf(false) }
    var showPad by rememberSaveable { mutableStateOf(false) }
    /** The text field or dropdown being filled in. */
    var editingField by remember { mutableStateOf<Pair<Int, FormField>?>(null) }
    val fields by viewModel.fields.collectAsState()
    /** Where to put the signature once it's been drawn. */
    var signAt by remember { mutableStateOf<NewNote?>(null) }
    val signature by viewModel.signature.collectAsState()
    val signatureSize by viewModel.signatureSize.collectAsState()
    /** A password change waiting for the user to pick where the protected copy goes. */
    var pendingPassword by remember { mutableStateOf<ViewerViewModel.PasswordChange?>(null) }
    var newNote by remember { mutableStateOf<NewNote?>(null) }
    var tapped by remember { mutableStateOf<AnnotationTap?>(null) }
    /** Set while asking whether to save; runs once saved (or discarded, if allowed). */
    var unsavedPrompt by remember { mutableStateOf<Pair<() -> Unit, Boolean>?>(null) }
    var afterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    /** The word first selected by a long press, which dragging extends from. */
    var dragAnchor by remember { mutableStateOf<TextSelection?>(null) }

    val currentPage by remember {
        derivedStateOf { if (paged) pagerState.currentPage else zoom.currentPage(listState) }
    }

    /** Switches between page-by-page and continuous, staying on the same page. */
    fun setPaged(toPaged: Boolean) {
        val page = currentPage
        zoom.reset()
        if (toPaged) pagerState.requestScrollToPage(page) else listState.requestScrollToItem(page)
        viewModel.setPaged(toPaged)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { currentPage }.distinctUntilChanged().debounce(400).collect(onPageChanged)
    }

    val currentOnSavedAs by rememberUpdatedState(onSavedAs)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ViewerEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
                ViewerEvent.Saved -> {
                    scope.launch { snackbar.showSnackbar("Saved") }
                    afterSave?.invoke()
                    afterSave = null
                }
                is ViewerEvent.SavedAs -> {
                    afterSave = null
                    currentOnSavedAs(event.uri)
                }
            }
        }
    }

    // Save as / save a copy: the new file then opens (in place of this one) in the viewer.
    val copyPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) {
            afterSave = null
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.save(target = uri)
    }

    // Files we can't write to get the password change as a new copy.
    val protectedCopyPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val change = pendingPassword
        pendingPassword = null
        if (uri == null || change == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.setPassword(change.password, uri)
    }

    fun applyPassword(newPassword: String?) {
        if (annotate.canOverwrite) {
            viewModel.setPassword(newPassword)
        } else {
            pendingPassword = ViewerViewModel.PasswordChange(newPassword)
            protectedCopyPicker.launch(info.name)
        }
    }

    fun save(then: (() -> Unit)? = null) {
        afterSave = then
        if (annotate.canOverwrite) viewModel.save()
        else copyPicker.launch(info.name.removeSuffix(".pdf").removeSuffix(".PDF") + " (annotated).pdf")
    }

    fun leave() {
        when {
            selection != null -> viewModel.clearSelection()
            searchOpen -> {
                searchOpen = false
                viewModel.clearSearch()
            }
            annotate.active -> viewModel.setAnnotating(false)
            annotate.dirty -> unsavedPrompt = onBack to true
            else -> onBack()
        }
    }
    BackHandler(onBack = ::leave)

    fun share() = scope.launch {
        val file = viewModel.exportCopy() ?: return@launch
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null))
    }

    fun print() = scope.launch {
        val file = viewModel.exportCopy(flattened = true) ?: return@launch
        val printing = context.getSystemService(android.print.PrintManager::class.java) ?: return@launch
        printing.print(file.nameWithoutExtension, PdfPrintAdapter(file), null)
    }

    val systemBars = WindowInsets.systemBars.asPaddingValues()
    val showTabs = tabs != null && tabs.tabs.isNotEmpty()
    val contentPadding = PaddingValues(
        top = systemBars.calculateTopPadding() + 64.dp + (if (showTabs) TabStripHeight else 0.dp) + PageGap,
        bottom = systemBars.calculateBottomPadding() + 72.dp,
    )

    /** Scrolls so [page] is showing; with [yPt]/[xPt], so that point on it is in view. */
    fun goTo(page: Int, yPt: Float? = null, xPt: Float? = null) {
        scope.launch {
            val width = zoom.viewport.width.toFloat()
            val pxPerPt = width / info.pageSizes[page].width
            val before = listState.layoutInfo.beforeContentPadding
            if (paged) {
                zoom.reset()
                pagerState.scrollToPage(page)
                return@launch
            }
            zoom.setOffsets(y = 0f)
            if (yPt == null) {
                listState.scrollToItem(page)
            } else {
                // Put the point about a third of the way down the screen.
                val target = before + yPt * pxPerPt - zoom.viewport.height / (3f * zoom.scale)
                listState.scrollToItem(page, scrollOffset = max(0f, target).toInt())
            }
            if (xPt != null && zoom.isZoomed) {
                zoom.setOffsets(x = width / 2f - xPt * pxPerPt * zoom.scale)
            }
        }
    }

    // Follow the selected search hit.
    LaunchedEffect(search.currentHit) {
        val hit = search.currentHit ?: return@LaunchedEffect
        val r = hit.rects.first()
        goTo(hit.page, yPt = r.top, xPt = (r.left + r.right) / 2f)
    }

    val density = LocalDensity.current
    val actions = remember(viewModel) {
        object : PageActions {
            /** Touch tolerance for tapping small annotations, in points on this page. */
            fun slop(page: Int): Float = with(density) { 12.dp.toPx() } / zoom.scale *
                info.pageSizes[page].width / zoom.viewport.width.coerceAtLeast(1)

            override fun onTap(page: Int, point: PdfPoint) {
                if (viewModel.selection.value != null) {
                    viewModel.clearSelection()
                    return
                }
                val state = viewModel.annotate.value
                when (state.currentTool) {
                    Tool.NOTE -> newNote = NewNote(page, PdfPoint(point.x - 12f, point.y - 12f))
                    Tool.SIGNATURE -> if (viewModel.signature.value == null) {
                        signAt = NewNote(page, point) // draw it first, then it goes here
                        showPad = true
                    } else {
                        viewModel.placeSignature(page, point)
                    }
                    Tool.SELECT -> scope.launch {
                        viewModel.annotationAt(page, point, slop(page))?.let { tapped = AnnotationTap(page, it) }
                    }
                    null -> scope.launch {
                        viewModel.fieldAt(page, point)?.let { field ->
                            when (field.kind) {
                                FieldKind.CHECKBOX, FieldKind.RADIO -> viewModel.toggleField(page, field.id)
                                else -> editingField = page to field
                            }
                            return@launch
                        }
                        val note = viewModel.annotationAt(page, point, slop(page))?.takeIf { it.kind == AnnotationKind.NOTE }
                        if (note != null) {
                            tapped = AnnotationTap(page, note)
                            return@launch
                        }
                        when (val link = viewModel.linkAt(page, point.x, point.y)) {
                            is LinkTarget.Page -> goTo(link.index)
                            is LinkTarget.External -> openExternal(context, link.uri)
                            null -> if (!searchOpen) chromeVisible = !chromeVisible
                        }
                    }
                    else -> Unit
                }
            }

            override fun onLongPress(page: Int, point: PdfPoint) {
                dragAnchor = null
                viewModel.selectWord(page, point)
            }

            override fun onLongPressDrag(page: Int, point: PdfPoint) {
                val anchor = dragAnchor ?: viewModel.selection.value?.takeIf { it.page == page }?.also { dragAnchor = it }
                    ?: return
                // Extend from the long-pressed word towards the finger, in reading order.
                val line = anchor.rects.first().let { it.bottom - it.top }
                val forward = point.y > anchor.start.y + line / 2 ||
                    (point.y > anchor.start.y - line / 2 && point.x >= anchor.start.x)
                if (forward) viewModel.selectRange(page, anchor.start, point)
                else viewModel.selectRange(page, point, anchor.end)
            }

            override fun onSelectionHandles(page: Int, start: PdfPoint, end: PdfPoint) =
                viewModel.selectRange(page, start, end)

            override fun onStroke(page: Int, points: List<PdfPoint>) = viewModel.addStroke(page, points)

            override fun onErase(page: Int, points: List<PdfPoint>, radius: Float, gesture: Int) =
                viewModel.erase(page, points, radius, gesture)

            override fun onTextHighlight(page: Int, start: PdfPoint, end: PdfPoint, finished: Boolean) =
                viewModel.highlightDrag(page, start, end, finished)

            override fun onTextHighlightCancelled() = viewModel.clearSelection()

            override fun onStylusSeen() = viewModel.onStylusSeen()

            override fun onPageShown(page: Int) = viewModel.loadFields(page)
        }
    }

    val showChrome = chromeVisible || annotate.active || selection != null

    Box(Modifier.fillMaxSize()) {
        DocumentView(
            pageSizes = info.pageSizes,
            source = viewModel,
            listState = listState,
            pagerState = pagerState,
            paged = paged,
            zoom = zoom,
            contentPadding = contentPadding,
            searchHits = search.hits,
            currentHit = search.currentHit,
            layer = AnnotationLayer(
                tool = annotate.currentTool,
                inkStyle = annotate.inkStyle,
                fingerDraws = annotate.fingerDraws,
                pending = pendingInk,
                selection = selection,
                versions = versions,
                fields = fields,
            ),
            actions = actions,
        )

        AnimatedVisibility(
            visible = showChrome || searchOpen,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val barColors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            )
            when {
                selection != null -> SelectionBar(
                    canMark = annotate.editable,
                    onCopy = {
                        selection?.text?.let { copyToClipboard(context, it) }
                        viewModel.clearSelection()
                    },
                    onHighlight = { viewModel.markupSelection(MarkupKind.HIGHLIGHT) },
                    onUnderline = { viewModel.markupSelection(MarkupKind.UNDERLINE) },
                    onStrikeout = { viewModel.markupSelection(MarkupKind.STRIKEOUT) },
                    onClose = viewModel::clearSelection,
                )
                searchOpen -> SearchBar(
                    state = search,
                    onSearch = { viewModel.search(it, fromPage = currentPage) },
                    onNext = viewModel::nextHit,
                    onPrevious = viewModel::previousHit,
                    onClose = {
                        searchOpen = false
                        viewModel.clearSearch()
                    },
                )
                annotate.active -> TopAppBar(
                    title = { Text("Annotate") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.setAnnotating(false) }) { Icon(Icons.Filled.Check, "Done annotating") }
                    },
                    actions = {
                        IconButton(onClick = viewModel::undo, enabled = annotate.edits.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                        }
                        IconButton(onClick = viewModel::redo, enabled = annotate.edits.canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, "Redo")
                        }
                        IconButton(onClick = { save() }, enabled = annotate.dirty) { Icon(Icons.Filled.Save, "Save") }
                    },
                    colors = barColors,
                )
                else -> Column {
                    TopAppBar(
                        title = { Text(info.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        },
                        actions = {
                            if (annotate.dirty) IconButton(onClick = { save() }) { Icon(Icons.Filled.Save, "Save") }
                            IconButton(onClick = { searchOpen = true }) { Icon(Icons.Filled.Search, "Search") }
                            if (annotate.editable) {
                                IconButton(onClick = { viewModel.setAnnotating(true) }) { Icon(Icons.Filled.Draw, "Annotate") }
                            }
                            MoreMenu(
                                paged = paged,
                                canOrganize = onOrganize != null && annotate.editable,
                                canSave = annotate.editable,
                                onContents = { showOutline = true },
                                onOrganize = {
                                    if (annotate.dirty) unsavedPrompt = { onOrganize?.invoke(currentPage) ?: Unit } to false
                                    else onOrganize?.invoke(currentPage)
                                },
                                onScanInto = {
                                    // Like organizing, this rewrites the file: unsaved annotations first.
                                    if (annotate.dirty) unsavedPrompt = { onScanInto?.invoke(currentPage) ?: Unit } to false
                                    else onScanInto?.invoke(currentPage)
                                },
                                onPaged = ::setPaged,
                                onShare = { share() },
                                onSaveAs = { copyPicker.launch(info.name) },
                                onPrint = { print() },
                                onPassword = { showProtect = true },
                                onCompress = viewModel::compress,
                                onRecognize = viewModel::recognizeText,
                            )
                        },
                        colors = barColors,
                    )
                    if (showTabs) TabStrip(tabs!!, onClose = {
                        if (annotate.dirty) unsavedPrompt = tabs.onClose to true else tabs.onClose()
                    })
                }
            }
        }

        AnimatedVisibility(
            visible = annotate.active,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            AnnotateToolbar(
                state = annotate,
                onTool = { tool ->
                    when {
                        tool == annotate.tool && tool in setOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.TEXT_HIGHLIGHT) -> showStyle = true
                        // Picking the signature tool without a signature, or picking it again: the pad.
                        tool == Tool.SIGNATURE && (signature == null || annotate.tool == Tool.SIGNATURE) -> {
                            viewModel.selectTool(tool)
                            showPad = true
                        }
                        else -> viewModel.selectTool(tool)
                    }
                },
                onStyle = { showStyle = true },
            )
        }

        AnimatedVisibility(
            visible = showChrome,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp)
                .padding(bottom = if (annotate.active) 64.dp else 0.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.clickable { showGoTo = true },
            ) {
                Text(
                    "${currentPage + 1} / $pageCount",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (annotate.active) 72.dp else 56.dp),
        )
    }

    if (showOutline) {
        OutlineSheet(
            viewModel = viewModel,
            onDismiss = { showOutline = false },
            onSelect = { page ->
                showOutline = false
                goTo(page)
            },
        )
    }

    if (showGoTo) {
        GoToPageDialog(
            pageCount = pageCount,
            currentPage = currentPage,
            onDismiss = { showGoTo = false },
            onGo = { page ->
                showGoTo = false
                goTo(page)
            },
        )
    }

    if (showStyle) {
        StyleSheet(
            state = annotate,
            onPen = viewModel::setPenStyle,
            onHighlighter = viewModel::setHighlighterStyle,
            onMarkupColor = viewModel::setMarkupColor,
            onFingerDraws = viewModel::setFingerDraws,
            onDismiss = { showStyle = false },
        )
    }

    newNote?.let { note ->
        NoteDialog(
            initialText = "",
            isNew = true,
            onSave = { text ->
                viewModel.addNote(note.page, note.point, text)
                newNote = null
            },
            onDelete = null,
            onDismiss = { newNote = null },
        )
    }

    tapped?.let { t ->
        if (t.info.kind == AnnotationKind.NOTE) {
            NoteDialog(
                initialText = t.info.contents,
                isNew = false,
                onSave = { text ->
                    viewModel.setNoteText(t.page, t.info.id, text)
                    tapped = null
                },
                onDelete = if (annotate.editable) {
                    {
                        viewModel.deleteAnnotation(t.page, t.info.id)
                        tapped = null
                    }
                } else null,
                onDismiss = { tapped = null },
            )
        } else {
            DeleteAnnotationDialog(
                kind = t.info.kind,
                onDelete = {
                    viewModel.deleteAnnotation(t.page, t.info.id)
                    tapped = null
                },
                onDismiss = { tapped = null },
            )
        }
    }

    unsavedPrompt?.let { (then, canDiscard) ->
        UnsavedChangesDialog(
            onSave = {
                unsavedPrompt = null
                save(then)
            },
            onDiscard = if (canDiscard) {
                {
                    unsavedPrompt = null
                    viewModel.discardChanges()
                    then()
                }
            } else null,
            onDismiss = { unsavedPrompt = null },
        )
    }

    editingField?.let { (page, field) ->
        if (field.kind == FieldKind.CHOICE) {
            ChoiceFieldDialog(
                field = field,
                onChoose = {
                    editingField = null
                    viewModel.chooseField(page, field.id, it)
                },
                onDismiss = { editingField = null },
            )
        } else {
            TextFieldDialog(
                field = field,
                onDone = {
                    editingField = null
                    viewModel.fillText(page, field.id, it)
                },
                onDismiss = { editingField = null },
            )
        }
    }

    if (showPad) {
        SignaturePad(
            existing = signature,
            initialSize = signatureSize,
            onSave = { drawn, size ->
                showPad = false
                viewModel.saveSignature(drawn, size)
                signAt?.let { viewModel.placeSignature(it.page, it.point) }
                signAt = null
                if (drawn != null) scope.launch { snackbar.showSnackbar("Tap the page where your signature should go") }
            },
            onDismiss = {
                showPad = false
                signAt = null
            },
        )
    }

    if (showProtect) {
        ProtectDialog(
            encrypted = annotate.encrypted,
            onSet = {
                showProtect = false
                applyPassword(it)
            },
            onRemove = {
                showProtect = false
                applyPassword(null)
            },
            onDismiss = { showProtect = false },
        )
    }

    val compressed by viewModel.compressed.collectAsState()
    val compressedCopyPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult viewModel.dismissCompressed()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.keepCompressed(uri)
    }
    var pickingCompressedCopy by remember { mutableStateOf(false) }
    compressed?.takeIf { !pickingCompressedCopy }?.let { result ->
        CompressedDialog(
            before = result.before,
            after = result.after,
            canOverwrite = annotate.canOverwrite,
            onReplace = { viewModel.keepCompressed() },
            onSaveCopy = {
                pickingCompressedCopy = true
                compressedCopyPicker.launch(info.name.removeSuffix(".pdf").removeSuffix(".PDF") + " (smaller).pdf")
            },
            onDismiss = viewModel::dismissCompressed,
        )
    }
    LaunchedEffect(compressed) { if (compressed == null) pickingCompressedCopy = false }

    if (annotate.saving) SavingDialog(annotate.working ?: "Saving…")
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Text from PDF", text))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    state: SearchState,
    onSearch: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(state.query) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close search") }
        },
        title = {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Search") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSearch(text)
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        actions = {
            val label = when {
                state.query.isEmpty() -> ""
                state.hits.isEmpty() -> if (state.running) "…" else "0"
                else -> "${state.current + 1}/${state.hits.size}${if (state.running) "+" else ""}"
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = onPrevious, enabled = state.hits.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowUp, "Previous match")
            }
            IconButton(onClick = onNext, enabled = state.hits.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowDown, "Next match")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(viewModel: ViewerViewModel, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    val entries by produceState<List<org.szabadpdf.core.pdf.OutlineEntry>?>(null) { value = viewModel.outline() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Contents",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        when {
            entries == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            entries!!.isEmpty() -> Text(
                "This document has no table of contents.",
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(entries!!) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                entry.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = (entry.depth * 16).dp),
                            )
                        },
                        trailingContent = {
                            if (entry.pageIndex >= 0) Text("${entry.pageIndex + 1}", style = MaterialTheme.typography.labelLarge)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable(enabled = entry.pageIndex >= 0) { onSelect(entry.pageIndex) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, currentPage: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    // Pre-selected, so typing replaces the current page number.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val initial = "${currentPage + 1}"
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val text = field.text
    val page = text.toIntOrNull()?.takeIf { it in 1..pageCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            OutlinedTextField(
                modifier = Modifier.focusRequester(focus),
                value = field,
                onValueChange = { field = it.copy(text = it.text.filter(Char::isDigit).take(6)) },
                label = { Text("Page (1–$pageCount)") },
                singleLine = true,
                isError = text.isNotEmpty() && page == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { page?.let { onGo(it - 1) } }),
            )
        },
        confirmButton = {
            TextButton(onClick = { page?.let { onGo(it - 1) } }, enabled = page != null) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(wrongPassword: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Password required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This document is protected. Enter its password to open it.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = wrongPassword,
                    supportingText = { if (wrongPassword) Text("Wrong password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(password) }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(password) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Couldn't open this document", style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

private fun openExternal(context: android.content.Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    } catch (_: ActivityNotFoundException) {
        // Nothing can open it; ignore.
    }
}
