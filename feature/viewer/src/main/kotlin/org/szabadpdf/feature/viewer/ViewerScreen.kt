package org.szabadpdf.feature.viewer

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        ViewerState.Idle, ViewerState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is ViewerState.Error -> ErrorScreen(s.message, onBack)
        is ViewerState.NeedsPassword -> PasswordDialog(s.wrongPassword, viewModel::submitPassword, onBack)
        is ViewerState.Ready -> key(s.document.uri) {
            DocumentScreen(s.document, viewModel, onBack, onPageChanged, onOrganize)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun DocumentScreen(
    info: DocumentInfo,
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    onPageChanged: (Int) -> Unit,
    onOrganize: ((Int) -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = info.initialPage)
    val zoom = remember { ZoomState() }
    val search by viewModel.search.collectAsState()
    val pageCount = info.pageSizes.size

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var showOutline by rememberSaveable { mutableStateOf(false) }
    var showGoTo by rememberSaveable { mutableStateOf(false) }

    val currentPage by remember { derivedStateOf { zoom.currentPage(listState) } }

    LaunchedEffect(Unit) {
        snapshotFlow { currentPage }.distinctUntilChanged().debounce(400).collect(onPageChanged)
    }

    val systemBars = WindowInsets.systemBars.asPaddingValues()
    val contentPadding = PaddingValues(
        top = systemBars.calculateTopPadding() + 64.dp + PageGap,
        bottom = systemBars.calculateBottomPadding() + 72.dp,
    )

    /** Scrolls so [page] is showing; with [yPt]/[xPt], so that point on it is in view. */
    fun goTo(page: Int, yPt: Float? = null, xPt: Float? = null) {
        scope.launch {
            val width = zoom.viewport.width.toFloat()
            val pxPerPt = width / info.pageSizes[page].width
            val before = listState.layoutInfo.beforeContentPadding
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

    Box(Modifier.fillMaxSize()) {
        DocumentView(
            pageSizes = info.pageSizes,
            source = viewModel,
            listState = listState,
            zoom = zoom,
            contentPadding = contentPadding,
            searchHits = search.hits,
            currentHit = search.currentHit,
            onTap = { page, x, y ->
                scope.launch {
                    when (val link = viewModel.linkAt(page, x, y)) {
                        is LinkTarget.Page -> goTo(link.index)
                        is LinkTarget.External -> openExternal(context, link.uri)
                        null -> if (!searchOpen) chromeVisible = !chromeVisible
                    }
                }
            },
        )

        AnimatedVisibility(
            visible = chromeVisible || searchOpen,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            if (searchOpen) {
                SearchBar(
                    state = search,
                    onSearch = { viewModel.search(it, fromPage = currentPage) },
                    onNext = viewModel::nextHit,
                    onPrevious = viewModel::previousHit,
                    onClose = {
                        searchOpen = false
                        viewModel.clearSearch()
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(info.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { searchOpen = true }) { Icon(Icons.Filled.Search, "Search") }
                        IconButton(onClick = { showOutline = true }) { Icon(Icons.AutoMirrored.Filled.List, "Contents") }
                        if (onOrganize != null) {
                            IconButton(onClick = { onOrganize(currentPage) }) { Icon(Icons.Filled.GridView, "Organize pages") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    ),
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
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
