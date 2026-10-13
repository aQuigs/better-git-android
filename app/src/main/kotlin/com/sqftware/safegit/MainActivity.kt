package com.sqftware.safegit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.storage.AllFilesAccess
import com.sqftware.safegit.storage.folderOf
import com.sqftware.safegit.sync.Credentials
import com.sqftware.safegit.sync.RepoSetup
import com.sqftware.safegit.ui.AccessScreen
import com.sqftware.safegit.ui.AddRepoScreen
import com.sqftware.safegit.ui.RepoRow
import com.sqftware.safegit.ui.ReposScreen
import com.sqftware.safegit.work.Syncer
import com.sqftware.safegit.work.WorkSyncer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val access by lazy { AllFilesAccess(this) }
    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val services = Services.of(this)
        val syncer = WorkSyncer(this)
        setContent {
            MaterialTheme(colorScheme = colors()) {
                if (!granted) {
                    AccessScreen(onGrant = access::request)
                } else {
                    App(services, syncer)
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
private fun App(services: Services, syncer: Syncer) {
    var repos by remember { mutableStateOf(services.repos.all()) }
    var adding by remember { mutableStateOf(false) }
    val states by syncer.states.collectAsState(emptyMap())

    if (!adding) {
        ReposScreen(
            rows = repos.map { RepoRow(it, states[it.id]) },
            onSync = syncer::sync,
            onAdd = { adding = true },
        )
        return
    }

    var folder by remember { mutableStateOf<File?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val unusable = stringResource(R.string.add_folder_unusable)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            folder = folderOf(tree)
            error = if (folder == null) unusable else null
        }
    }
    val scope = rememberCoroutineScope()
    AddRepoScreen(
        folder = folder,
        connecting = connecting,
        error = error,
        onPickFolder = { pick.launch(null) },
        onConnect = { url ->
            val chosen = folder ?: return@AddRepoScreen
            connecting = true
            scope.launch {
                val id = UUID.randomUUID().toString()
                val result = withContext(Dispatchers.IO) {
                    RepoSetup(services.git, services.identity).connect(url, services.gitDir(id), chosen, Credentials.NONE)
                }
                connecting = false
                when (result) {
                    is RepoSetup.Result.Connected -> {
                        val saved = SavedRepo(id, url, chosen)
                        services.repos.save(saved)
                        repos = services.repos.all()
                        adding = false
                        syncer.sync(saved)
                    }
                    is RepoSetup.Result.Failed -> error = result.detail.ifEmpty { result.problem.name }
                }
            }
        },
        onCancel = { adding = false },
    )
}

@Composable
private fun colors() = when {
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    isSystemInDarkTheme() -> darkColorScheme()
    else -> MaterialTheme.colorScheme
}
