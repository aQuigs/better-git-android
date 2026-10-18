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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.sqftware.safegit.about.Licenses
import com.sqftware.safegit.auth.DeviceCode
import com.sqftware.safegit.repos.AutoSync
import com.sqftware.safegit.repos.Connector
import com.sqftware.safegit.repos.RemoteBranches
import com.sqftware.safegit.storage.AllFilesAccess
import com.sqftware.safegit.storage.StorageAccess
import com.sqftware.safegit.storage.folderOf
import com.sqftware.safegit.ui.AboutScreen
import com.sqftware.safegit.ui.AccessScreen
import com.sqftware.safegit.ui.AccountCard
import com.sqftware.safegit.ui.AddRepoScreen
import com.sqftware.safegit.ui.RepoRow
import com.sqftware.safegit.ui.ReposScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
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

@Composable
private fun App(services: Services) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    val connecting by services.connector.state.collectAsState()

    // Also when the user left the add screen while it connected
    LaunchedEffect(connecting) {
        if (connecting is Connector.State.Connected) {
            services.connector.reset()
            adding = false
        }
    }

    when {
        adding -> AddRepo(services, connecting, onDone = { adding = false })
        about -> About(services, onDone = { about = false })
        else -> Repos(services, onAdd = { adding = true }, onAbout = { about = true })
    }
}

@Composable
private fun About(services: Services, onDone: () -> Unit) {
    val context = LocalContext.current
    val licenses by produceState<Licenses?>(null) { value = withContext(Dispatchers.IO) { services.licenses.load() } }
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    BackHandler(onBack = onDone)

    AboutScreen("${info.versionName} (${info.longVersionCode})", licenses, onOpen = { open(context, it) })
}

@Composable
private fun Repos(services: Services, onAdd: () -> Unit, onAbout: () -> Unit) {
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
        onAbout = onAbout,
        all = all,
        onSyncAll = services.syncer::syncAll,
        onCancelAll = services.syncer::cancelAll,
        onCloseAll = services.syncer::closeAll,
        preview = services::preview,
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
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(code.userCode, code.userCode))
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
