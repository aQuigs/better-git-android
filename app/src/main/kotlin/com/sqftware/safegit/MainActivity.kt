package com.sqftware.safegit

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.sqftware.safegit.about.Licenses
import com.sqftware.safegit.about.appVersion
import com.sqftware.safegit.auth.DeviceCode
import com.sqftware.safegit.repos.AutoSync
import com.sqftware.safegit.repos.Connector
import com.sqftware.safegit.repos.RemoteBranches
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.storage.AllFilesAccess
import com.sqftware.safegit.storage.StorageAccess
import com.sqftware.safegit.storage.folderOf
import com.sqftware.safegit.sync.FileVersions
import com.sqftware.safegit.sync.RepoFiles
import com.sqftware.safegit.ui.AboutScreen
import com.sqftware.safegit.ui.AccessScreen
import com.sqftware.safegit.ui.AccountCard
import com.sqftware.safegit.ui.AddRepoScreen
import com.sqftware.safegit.ui.FilesScreen
import com.sqftware.safegit.ui.HistoryScreen
import com.sqftware.safegit.ui.ListDetail
import com.sqftware.safegit.ui.RepoRow
import com.sqftware.safegit.ui.ReposScreen
import com.sqftware.safegit.ui.ResolveScreen
import com.sqftware.safegit.ui.RestoreOutcome
import com.sqftware.safegit.ui.VersionsScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val access: StorageAccess by lazy { AllFilesAccess(this) }
    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val services = Services.of(this)
        setContent {
            MaterialTheme(colorScheme = colors()) {
                if (!granted) {
                    AccessScreen(onGrant = access::request)
                } else {
                    App(services)
                }
            }
        }
    }

    // What each repo would push is read as the app comes to the front, rather than watched
    override fun onStart() {
        super.onStart()
        Services.of(this).outgoing.show()
    }

    // A rotation is no trip to the back, and need not read every repo again
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) Services.of(this).outgoing.hide()
    }

    // The user grants all-files access in Settings and comes back here
    override fun onResume() {
        super.onResume()
        granted = access.granted()
    }
}

/** What opens from the repo list: beside it on a wide window, in its place on a phone. */
private sealed interface Detail {
    data object Add : Detail
    data object About : Detail
    data class History(val repo: String) : Detail
    data class Files(val repo: String) : Detail
    /** The repo's id and the path of the marked file open */
    data class Resolve(val repo: String, val path: String) : Detail

    companion object {
        val saver = listSaver<Detail?, String>(
            save = {
                when (it) {
                    null -> emptyList()
                    Add -> listOf("add")
                    About -> listOf("about")
                    is History -> listOf("history", it.repo)
                    is Files -> listOf("files", it.repo)
                    is Resolve -> listOf("resolve", it.repo, it.path)
                }
            },
            restore = {
                when (it.firstOrNull()) {
                    "add" -> Add
                    "about" -> About
                    "history" -> History(it[1])
                    "files" -> Files(it[1])
                    "resolve" -> Resolve(it[1], it[2])
                    else -> null
                }
            },
        )
    }
}

private val Detail?.repo get() = when (this) {
    is Detail.History -> repo
    is Detail.Files -> repo
    is Detail.Resolve -> repo
    else -> null
}

@Composable
private fun App(services: Services) {
    var detail by rememberSaveable(stateSaver = Detail.saver) { mutableStateOf(null) }
    val connecting by services.connector.state.collectAsState()

    // Also when the user left the add screen while it connected
    LaunchedEffect(connecting) {
        if (connecting is Connector.State.Connected) {
            services.connector.reset()
            if (detail == Detail.Add) detail = null
        }
    }

    val close = { detail = null }
    ListDetail(
        list = {
            Repos(
                services,
                open = detail.repo,
                onAdd = { detail = Detail.Add },
                onAbout = { detail = Detail.About },
                onHistory = { detail = Detail.History(it.id) },
                onFiles = { detail = Detail.Files(it.id) },
                onResolve = { repo, path -> detail = Detail.Resolve(repo.id, path) },
            )
        },
        detail = when (val shown = detail) {
            null -> null
            // Keyed, so a screen opened for another repo or file starts afresh rather than with what this one remembered
            else -> { { key(shown) { DetailScreen(services, shown, connecting, close) } } }
        },
    )
}

@Composable
private fun DetailScreen(services: Services, detail: Detail, connecting: Connector.State, onDone: () -> Unit) = when (detail) {
    Detail.Add -> AddRepo(services, connecting, onDone)
    Detail.About -> About(services, onDone)
    is Detail.History -> History(services, detail.repo, onDone)
    is Detail.Files -> Files(services, detail.repo, onDone)
    is Detail.Resolve -> Resolve(services, detail.repo, detail.path, onDone)
}

@Composable
private fun About(services: Services, onDone: () -> Unit) {
    val context = LocalContext.current
    val licenses by produceState<Licenses?>(null) { value = withContext(Dispatchers.IO) { services.licenses.load() } }
    val scope = rememberCoroutineScope()
    var copied by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onDone)

    AboutScreen(
        context.appVersion(),
        licenses,
        onOpen = { open(context, it) },
        onCopyDiagnostics = {
            scope.launch {
                copy(context, "SafeGit diagnostics", withContext(Dispatchers.IO) { services.diagnostics.report() })
                copied = true
            }
        },
        copied = copied,
    )
}

private fun copy(context: Context, label: String, text: String) =
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))

@Composable
private fun History(services: Services, id: String, onDone: () -> Unit) {
    val repos by services.repos.all.collectAsState(null)
    val syncs by remember(id) { services.history.of(id) }.collectAsState(null)
    var file by rememberSaveable { mutableStateOf<String?>(null) }
    val saved = repos?.find { it.id == id }
    BackHandler { if (file != null) file = null else onDone() }

    val shown = file
    if (shown != null && saved != null) {
        Versions(services, saved, shown)
    } else {
        HistoryScreen(saved?.name.orEmpty(), syncs, onFile = { file = it })
    }
}

@Composable
private fun Files(services: Services, id: String, onDone: () -> Unit) {
    val saved = services.repos.all.collectAsState(null).value?.find { it.id == id }
    var folder by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var file by rememberSaveable { mutableStateOf<String?>(null) }
    var files by remember(id) { mutableStateOf<RepoFiles?>(null) }
    var unreadable by remember(id) { mutableStateOf<String?>(null) }
    // Here, so a search and where it was scrolled outlast a look at a file's versions
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val open = { to: String ->
        folder = to
        scope.launch { list.scrollToItem(0) }
    }
    // Again on coming back from a file, which a restore may have brought back
    LaunchedEffect(saved, file == null) {
        if (saved == null || file != null) return@LaunchedEffect
        try {
            files = withContext(Dispatchers.IO) { services.files(saved) }
        } catch (e: Exception) {
            unreadable = e.message.orEmpty()
        }
    }
    BackHandler {
        when {
            file != null -> file = null
            query.isNotEmpty() -> query = ""
            folder.isNotEmpty() -> open(RepoFiles.parent(folder))
            else -> onDone()
        }
    }

    val shown = file
    if (shown != null && saved != null) {
        Versions(services, saved, shown)
    } else {
        FilesScreen(saved?.name.orEmpty(), files, folder, query, onQuery = { query = it }, onFolder = { open(it) }, onFile = { file = it }, unreadable, list)
    }
}

@Composable
private fun Versions(services: Services, saved: SavedRepo, path: String) {
    var versions by remember(path) { mutableStateOf<FileVersions?>(null) }
    var unreadable by remember(path) { mutableStateOf<String?>(null) }
    var restoring by remember(path) { mutableStateOf(false) }
    var outcome by remember(path) { mutableStateOf<RestoreOutcome?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path) {
        try {
            versions = withContext(Dispatchers.IO) { services.versions(saved, path) }
        } catch (e: Exception) {
            unreadable = e.message.orEmpty()
        }
    }

    VersionsScreen(
        path,
        versions,
        preview = { version -> versions?.let { services.preview(saved, path, it, version) } },
        onRestore = { version ->
            val seen = versions ?: return@VersionsScreen
            restoring = true
            scope.launch {
                outcome = withContext(Dispatchers.IO) {
                    try {
                        RestoreOutcome.Ended(services.restore(saved, path, seen, version))
                    } catch (e: Exception) {
                        RestoreOutcome.Failed(e.message.orEmpty())
                    }
                }
                restoring = false
            }
        },
        restoring = restoring,
        outcome = outcome,
        unreadable = unreadable,
    )
}

@Composable
private fun Resolve(services: Services, id: String, path: String, onDone: () -> Unit) {
    val repos = services.repos.all.collectAsState(null).value ?: return
    val repo = repos.firstOrNull { it.id == id }
    // Disconnected meanwhile
    if (repo == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    ResolveScreen(path, open = { services.openMarked(repo, path) }, save = { file, text -> services.saveMarked(repo, file, text) }, onDone)
}

@Composable
private fun Repos(
    services: Services,
    open: String?,
    onAdd: () -> Unit,
    onAbout: () -> Unit,
    onHistory: (SavedRepo) -> Unit,
    onFiles: (SavedRepo) -> Unit,
    onResolve: (SavedRepo, String) -> Unit,
) {
    val repos by services.repos.all.collectAsState(null)
    val states by services.syncer.states.collectAsState(emptyMap())
    val all by services.syncer.all.collectAsState(null)
    val account by services.signIn.state.collectAsState()
    val pending by services.outgoing.pending.collectAsState()
    val context = LocalContext.current
    // Automatic syncs run without it too; it only lets them say when they need the user
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    ReposScreen(
        rows = repos?.map { RepoRow(it, states[it.id], services.signIn.canSignInAgain(it.url, account), pending[it.id]) } ?: return,
        onSync = services.syncer::sync,
        onAdd = onAdd,
        onCancel = services.syncer::cancel,
        onSignIn = services.signIn::signInAgain,
        onAuto = { repo, auto ->
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (auto != AutoSync.OFF && !granted) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            services.syncer.setAuto(repo, auto)
        },
        onMessage = services.syncer::setMessage,
        device = services.device,
        onHistory = onHistory,
        onFiles = onFiles,
        onAbout = onAbout,
        all = all,
        onSyncAll = services.syncer::syncAll,
        onCancelAll = services.syncer::cancelAll,
        onCloseAll = services.syncer::closeAll,
        preview = services::preview,
        onResolve = onResolve,
        selected = open,
        header = {
            AccountCard(
                account,
                onSignIn = services.signIn::signIn,
                onOpen = { code -> openCode(context, code) },
                onCancel = services.signIn::cancel,
                onSignOut = services.signIn::signOut,
            )
        },
    )
}

/** Copies the code for pasting on GitHub's page, and opens it; the card shows the address for a phone with no browser. */
private fun openCode(context: Context, code: DeviceCode) {
    copy(context, code.userCode, code.userCode)
    open(context, code.verificationUri)
}

private fun open(context: Context, uri: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
private fun AddRepo(services: Services, state: Connector.State, onDone: () -> Unit) {
    val connector = services.connector
    val choices by services.accountRepos.state.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }
    val branches by remember(url) { services.branches.of(url) }.collectAsState(RemoteBranches.State.Loading(url))
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var unusable by rememberSaveable { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            folder = folderOf(tree)?.path
            unusable = folder == null
            connector.reset()
        }
    }
    val cancel = {
        connector.reset()
        onDone()
    }
    BackHandler(enabled = state != Connector.State.Connecting, onBack = cancel)

    AddRepoScreen(
        folder = folder?.let(::File),
        folderUnusable = unusable,
        state = state,
        onPickFolder = { pick.launch(null) },
        onConnect = { url, branch -> folder?.let { connector.connect(url, File(it), branch) } },
        onCancel = cancel,
        choices = choices,
        branches = branches,
        onUrlChange = { url = it },
    )
}

@Composable
private fun colors() = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    isSystemInDarkTheme() -> darkColorScheme()
    else -> lightColorScheme()
}
