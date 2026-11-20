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
import org.szabadpdf.feature.viewer.ViewerScreen
import org.szabadpdf.feature.viewer.ViewerViewModel

class MainActivity : ComponentActivity() {

    private val viewer: ViewerViewModel by viewModels()
    private val organizer: OrganizeViewModel by viewModels()
    private lateinit var recents: RecentFiles

    /** A document handed to us by another app, waiting to be shown. */
    private var incoming by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        recents = RecentFiles(applicationContext)
        if (savedInstanceState == null) incoming = intent.documentUri()
        addOnNewIntentListener { it.documentUri()?.let { uri -> incoming = uri } }

        setContent {
            SzabadTheme {
                App(
                    viewer = viewer,
                    organizer = organizer,
                    recents = recents,
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
    viewer: ViewerViewModel,
    organizer: OrganizeViewModel,
    recents: RecentFiles,
    incoming: Uri?,
    onIncomingHandled: () -> Unit,
) {
    val context = LocalContext.current
    var openUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    /** Page to start the organize grid at, or -1 when not organizing. */
    var organizeFrom by rememberSaveable { mutableIntStateOf(-1) }

    fun show(uri: Uri) {
        organizer.discard()
        organizeFrom = -1
        val entry = recents.recordOpened(uri)
        viewer.open(uri, entry.name, entry.lastPage)
        openUri = uri
    }

    fun closeDocument() {
        organizer.discard()
        organizeFrom = -1
        viewer.close()
        openUri = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep access across restarts so the file can be reopened from Recent.
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

    // After a configuration change the view model still has the document; after process
    // death it doesn't, so reopen it.
    LaunchedEffect(openUri) {
        openUri?.let { uri ->
            val entry = recents.get(uri)
            viewer.open(uri, entry?.name ?: "Document", entry?.lastPage ?: 0)
        }
    }

    val current = openUri
    if (current == null) {
        val items by recents.items.collectAsState()
        HomeScreen(
            recents = items,
            onOpenPicker = { picker.launch(arrayOf("application/pdf")) },
            onOpenRecent = { show(it.uri) },
            onRemoveRecent = { recents.remove(it.uri) },
        )
    } else if (organizeFrom >= 0) {
        LaunchedEffect(current) {
            organizer.start(current, recents.get(current)?.name ?: "Document", viewer.password)
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
        BackHandler(onBack = ::closeDocument)
        ViewerScreen(
            viewModel = viewer,
            onBack = ::closeDocument,
            onPageChanged = { page -> recents.updatePage(current, page) },
            onOrganize = { page -> organizeFrom = page },
            onSavedAs = { copy -> show(copy) },
        )
    }
}
