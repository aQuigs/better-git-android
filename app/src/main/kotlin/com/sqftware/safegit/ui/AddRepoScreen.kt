package com.sqftware.safegit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.repos.AccountRepos
import com.sqftware.safegit.repos.Connector
import com.sqftware.safegit.repos.RemoteBranches
import java.io.File

/**
 * Connects a folder to a repo URL; [state] is how the last try went. [branches] are those of a URL passed to
 * [onUrlChange], to pick from; a connect without one takes the remote's default branch.
 */
@Composable
fun AddRepoScreen(
    folder: File?,
    folderUnusable: Boolean,
    state: Connector.State,
    onPickFolder: () -> Unit,
    onConnect: (url: String, branch: String?) -> Unit,
    onCancel: () -> Unit,
    choices: AccountRepos.State = AccountRepos.State.None,
    branches: RemoteBranches.State = RemoteBranches.State.None(""),
    onUrlChange: (String) -> Unit = {},
) {
    val connecting = state == Connector.State.Connecting
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val url = field.text
    LaunchedEffect(url) { onUrlChange(url) }
    var picked by rememberSaveable(url) { mutableStateOf<String?>(null) }

    // Until this URL's branches are in, a connect would quietly take the default, even over a pick made before a rotation
    val current = branches.takeIf { it.url == url } ?: RemoteBranches.State.Loading(url)
    val listed = current as? RemoteBranches.State.Listed
    // A remote naming no default gets the one a connect would take, or a pick: the first by name could be any
    val branch = listed?.let { picked?.takeIf { name -> name in it.names } ?: it.default ?: FALLBACK_BRANCH.takeIf { name -> name in it.names } }
    val ready = current !is RemoteBranches.State.Loading && (listed == null || branch != null)
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall)
            RepoField(field, onChange = { field = it }, choices)
            BranchField(current, branch, onPick = { picked = it }, enabled = !connecting)
            OutlinedButton(onClick = onPickFolder, enabled = !connecting) { Text(stringResource(R.string.add_folder)) }
            Text(folder?.path ?: stringResource(R.string.add_folder_none), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.add_folder_explain), style = MaterialTheme.typography.bodySmall)
            if (folderUnusable) Text(stringResource(R.string.add_folder_unusable), color = MaterialTheme.colorScheme.error)
            when (state) {
                is Connector.State.Failed -> Failure(state.problem, state.detail)
                is Connector.State.Refused -> Text(stringResource(refusalText(state.reason)), color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConnect(url, branch) }, enabled = !connecting && url.isNotEmpty() && folder != null && ready) {
                    Text(stringResource(if (connecting) R.string.add_connecting else R.string.add_connect))
                }
                TextButton(onClick = onCancel, enabled = !connecting) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

private fun refusalText(reason: Connector.Refusal) = when (reason) {
    Connector.Refusal.PASSWORD_IN_URL -> R.string.add_password_in_url
    Connector.Refusal.FOLDER_OVERLAPS -> R.string.add_folder_overlaps
}

/** The URL field, which also lists the account's repos whose name or URL holds what is typed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoField(field: TextFieldValue, onChange: (TextFieldValue) -> Unit, choices: AccountRepos.State) {
    val url = field.text
    val repos = (choices as? AccountRepos.State.Loaded)?.repos.orEmpty()
    // Most recently pushed first, so typing finds an older one; a menu of hundreds would jank as it opens
    val matches = repos.filter { it.name.contains(url, ignoreCase = true) || it.url.contains(url, ignoreCase = true) }.take(MENU_SIZE)
    var open by remember { mutableStateOf(false) }
    val expanded = open && matches.isNotEmpty()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = field,
            onValueChange = {
                onChange(it.copy(text = it.text.trim()))
                open = true
            },
            label = { Text(stringResource(R.string.add_url)) },
            placeholder = { Text(stringResource(R.string.add_url_hint)) },
            trailingIcon = when {
                choices == AccountRepos.State.Loading -> ({ CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) })
                repos.isNotEmpty() -> ({ ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
                else -> null
            },
            supportingText = when {
                repos.isNotEmpty() -> ({ Text(stringResource(R.string.add_url_pick)) })
                choices == AccountRepos.State.Failed -> ({ Text(stringResource(R.string.add_repos_failed)) })
                choices == AccountRepos.State.SignInRefused -> ({ Text(stringResource(R.string.add_sign_in_refused), color = MaterialTheme.colorScheme.error) })
                else -> null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = repos.isNotEmpty()),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { open = false }) {
            for (repo in matches) {
                DropdownMenuItem(
                    text = { Text(repo.name) },
                    onClick = {
                        onChange(TextFieldValue(repo.url, TextRange(repo.url.length)))
                        open = false
                    },
                )
            }
        }
    }
}

/** The branch to connect to, [picked] from the remote's [branches] once they are listed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchField(branches: RemoteBranches.State, picked: String?, onPick: (String) -> Unit, enabled: Boolean) {
    when (branches) {
        is RemoteBranches.State.None -> return
        is RemoteBranches.State.Failed -> return Text(stringResource(R.string.add_branches_failed), style = MaterialTheme.typography.bodySmall)
        else -> Unit
    }
    val listed = branches as? RemoteBranches.State.Listed
    var open by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = open && listed != null, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = picked?.let { branchText(it, listed?.default) }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled && listed != null,
            label = { Text(stringResource(R.string.add_branch)) },
            // Not a placeholder, which a labelled field shows only while focused
            supportingText = if (listed != null && picked == null) ({ Text(stringResource(R.string.add_branch_pick)) }) else null,
            trailingIcon = {
                if (listed == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else ExposedDropdownMenuDefaults.TrailingIcon(open)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled && listed != null),
        )
        ExposedDropdownMenu(expanded = open && listed != null, onDismissRequest = { open = false }) {
            for (name in listed?.names.orEmpty()) {
                DropdownMenuItem(
                    text = { Text(branchText(name, listed?.default)) },
                    onClick = {
                        onPick(name)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun branchText(name: String, default: String?) = if (name == default) stringResource(R.string.add_branch_default, name) else name

private const val MENU_SIZE = 50

/** The branch RepoSetup connects to when the remote names no default. */
private const val FALLBACK_BRANCH = "main"
