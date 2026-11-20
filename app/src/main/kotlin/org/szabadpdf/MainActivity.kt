package org.szabadpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import org.szabadpdf.feature.organize.OrganizeScreen
import org.szabadpdf.feature.organize.OrganizeViewModel
import org.szabadpdf.feature.scan.ScanScreen
import org.szabadpdf.feature.scan.ScanViewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import org.szabadpdf.feature.viewer.DocumentTab
import org.szabadpdf.feature.viewer.TabsUi
import org.szabadpdf.feature.viewer.ViewerScreen
import org.szabadpdf.feature.viewer.ViewerViewModel

class MainActivity : ComponentActivity() {

    private val organizer: OrganizeViewModel by viewModels()
    private val scanner: ScanViewModel by viewModels()
    private lateinit var recents: RecentFiles
    private lateinit var openTabs: OpenTabs

    /** A document handed to us by another app, waiting to be shown. */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        recents = RecentFiles(applicationContext)
        openTabs = OpenTabs(applicationContext)
        if (savedInstanceState == null) incoming = intent.documentUri()
        addOnNewIntentListener { it.documentUri()?.let { uri -> incoming = uri } }

        setContent {
            SzabadTheme {
                App(
                    organizer = organizer,
                    scanner = scanner,
                    recents = recents,
                    openTabs = openTabs,
                    incoming = incoming,
                    onIncomingHandled = { incoming = null },
                )
            }
        }
    }

    private fun Intent.documentUri(): Uri? = if (action == Intent.ACTION_VIEW) data else null
}

@Composable
private fun App(
    organizer: OrganizeViewModel,
    scanner: ScanViewModel,
    recents: RecentFiles,
    openTabs: OpenTabs,
    incoming: Uri?,
    onIncomingHandled: () -> Unit,
) {
    val context = LocalContext.current
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    val tabs by openTabs.tabs.collectAsState()
    val active by openTabs.active.collectAsState()
    val recentItems by recents.items.collectAsState()
    /** Showing the home screen even though tabs are open. */
    var home by rememberSaveable { mutableStateOf(false) }
    /** Page to start the organize grid at, or -1 when not organizing. */
    var organizeFrom by rememberSaveable { mutableIntStateOf(-1) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    // Each tab's scroll position and other screen state, kept while it's in the background.
    val tabStates = rememberSaveableStateHolder()

    /** Each open document has its own view model, so switching tabs keeps it open (edits too). */
    fun viewerFor(uri: Uri): ViewerViewModel = ViewModelProvider(owner)["viewer:$uri", ViewerViewModel::class.java]

    fun closeTab(uri: Uri) {
        viewerFor(uri).close()
        tabStates.removeState(uri.toString())
        openTabs.close(uri)
    }

    fun show(uri: Uri) {
        organizer.discard()
        organizeFrom = -1
        recents.recordOpened(uri)
        openTabs.open(uri).forEach(::closeTab)
        home = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep access across restarts so the file can be reopened from Recent and its tab.
            // Write access too where the provider allows it, so edits can be saved in place.
            val resolver = context.contentResolver
            runCatching {
                resolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.recoverCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            show(uri)
        }
    }

    LaunchedEffect(incoming) {
        incoming?.let {
            show(it)
            onIncomingHandled()
        }
    }

    fun nameOf(uri: Uri) = recentItems.firstOrNull { it.uri == uri }?.name ?: uri.lastPathSegment ?: "Document"

    val current = active
    if (scanning) {
        ScanScreen(
            viewModel = scanner,
            onSaved = { uri ->
                scanning = false
                show(uri)
            },
            onClose = { scanning = false },
        )
    } else if (current == null || home) {
        if (current != null) BackHandler { home = false }
        HomeScreen(
            openTabs = tabs.map { OpenTab(it, nameOf(it)) },
            recents = recentItems,
            onOpenPicker = { picker.launch(arrayOf("application/pdf")) },
            onScan = { scanning = true },
            onOpenTab = { show(it) },
            onOpenRecent = { show(it.uri) },
            onRemoveRecent = { recents.remove(it.uri, releaseAccess = it.uri !in tabs) },
        )
    } else if (organizeFrom >= 0) {
        val viewer = viewerFor(current)
        LaunchedEffect(current) {
            organizer.start(current, nameOf(current), viewer.password)
        }
        OrganizeScreen(
            viewModel = organizer,
            initialPage = organizeFrom,
            onClose = {
                organizer.discard()
                organizeFrom = -1
            },
            onSaved = { saved ->
                organizer.discard()
                organizeFrom = -1
                if (saved == current) {
                    val entry = recents.get(current)
                    viewer.open(current, entry?.name ?: "Document", entry?.lastPage ?: 0, reload = true)
                } else {
                    show(saved)
                }
            },
        )
    } else {
        val viewer = viewerFor(current)
        // Opens the document the first time its tab shows (also after the app was restarted).
        LaunchedEffect(current) {
            val entry = recents.get(current)
            viewer.open(current, entry?.name ?: "Document", entry?.lastPage ?: 0)
        }
        DisposableEffect(current) {
            onDispose { viewer.trimMemory() }
        }
        BackHandler { home = true }
        tabStates.SaveableStateProvider(current.toString()) {
            ViewerScreen(
                viewModel = viewer,
                onBack = { home = true },
                onPageChanged = { page -> recents.updatePage(current, page) },
                onOrganize = { page -> organizeFrom = page },
                onSavedAs = { copy ->
                    // The copy has the changes; the original stays as it was on disk.
                    viewer.close()
                    tabStates.removeState(current.toString())
                    recents.recordOpened(copy)
                    openTabs.replace(current, copy)
                },
                tabs = TabsUi(
                    tabs = tabs.map { DocumentTab(it.toString(), nameOf(it)) },
                    active = current.toString(),
                    onSelect = { id -> openTabs.select(Uri.parse(id)) },
                    onClose = { closeTab(current) },
                    onNew = { home = true },
                ),
            )
        }
    }
}
