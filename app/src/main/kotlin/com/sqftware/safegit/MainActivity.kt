package com.sqftware.safegit

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.sqftware.safegit.repos.Connector
import com.sqftware.safegit.storage.AllFilesAccess
import com.sqftware.safegit.storage.StorageAccess
import com.sqftware.safegit.storage.folderOf
import com.sqftware.safegit.ui.AccessScreen
import com.sqftware.safegit.ui.AddRepoScreen
import com.sqftware.safegit.ui.RepoRow
import com.sqftware.safegit.ui.ReposScreen
import java.io.File

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

    // The user grants all-files access in Settings and comes back here
    override fun onResume() {
        super.onResume()
        granted = access.granted()
    }
}

@Composable
private fun App(services: Services) {
    var adding by rememberSaveable { mutableStateOf(false) }
    val connecting by services.connector.state.collectAsState()

    // Also when the user left the add screen while it connected
    LaunchedEffect(connecting) {
        if (connecting is Connector.State.Connected) {
            services.connector.reset()
            adding = false
        }
    }

    if (adding) {
        AddRepo(services.connector, connecting, onDone = { adding = false })
    } else {
        Repos(services, onAdd = { adding = true })
    }
}

@Composable
private fun Repos(services: Services, onAdd: () -> Unit) {
    val repos by services.repos.all.collectAsState(null)
    val states by services.syncer.states.collectAsState(emptyMap())
    ReposScreen(
        rows = repos?.map { RepoRow(it, states[it.id]) } ?: return,
        onSync = services.syncer::sync,
        onAdd = onAdd,
    )
}

@Composable
private fun AddRepo(connector: Connector, state: Connector.State, onDone: () -> Unit) {
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
        onConnect = { url -> folder?.let { connector.connect(url, File(it)) } },
        onCancel = cancel,
    )
}

@Composable
private fun colors() = when {
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    isSystemInDarkTheme() -> darkColorScheme()
    else -> lightColorScheme()
}
