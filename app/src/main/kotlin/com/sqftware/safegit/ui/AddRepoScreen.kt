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
import java.io.File

/** Connects a folder to a repo URL; [state] is how the last try went. */
@Composable
fun AddRepoScreen(
    folder: File?,
    folderUnusable: Boolean,
    state: Connector.State,
    onPickFolder: () -> Unit,
    onConnect: (url: String) -> Unit,
    onCancel: () -> Unit,
    choices: AccountRepos.State = AccountRepos.State.None,
) {
    val connecting = state == Connector.State.Connecting
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val url = field.text
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.add_title), style = MaterialTheme.typography.headlineSmall)
            RepoField(field, onChange = { field = it }, choices)
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
                Button(onClick = { onConnect(url) }, enabled = !connecting && url.isNotEmpty() && folder != null) {
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

private const val MENU_SIZE = 50
