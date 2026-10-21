package com.sqftware.safegit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.sync.RepoFiles

/**
 * [name]'s files that have versions, [folder] by [folder] ("" for the top), or those whose path holds [query];
 * [files] is null while they load, or for good when they are [unreadable]. A folder opens with [onFolder], and a file's
 * versions with [onFile].
 */
@Composable
fun FilesScreen(
    name: String,
    files: RepoFiles?,
    folder: String,
    query: String,
    onQuery: (String) -> Unit,
    onFolder: (String) -> Unit,
    onFile: (String) -> Unit,
    unreadable: String? = null,
    list: LazyListState = rememberLazyListState(),
) {
    val entries = files?.let { if (query.isBlank()) it.inFolder(folder) else it.matching(query.trim()) }

    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), list, contentPadding = PaddingValues(16.dp)) {
            item {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.files_title), style = MaterialTheme.typography.headlineSmall)
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        query,
                        onQuery,
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.files_search)) },
                        singleLine = true,
                    )
                }
            }
            unreadable?.let { item { Text(stringResource(R.string.files_load_failed, it), style = MaterialTheme.typography.bodyLarge) } }
            if (entries == null) {
                if (unreadable == null) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) }
                return@LazyColumn
            }
            if (query.isBlank() && folder.isNotEmpty()) {
                // Named for where it leads
                val up = RepoFiles.parent(folder)
                item { EntryRow(stringResource(R.string.files_up, up.ifEmpty { name }), onClick = { onFolder(up) }) }
            }
            if (entries.isEmpty()) {
                item { Text(stringResource(if (query.isBlank()) R.string.files_empty else R.string.files_none), Modifier.padding(top = 8.dp)) }
            }
            items(entries, key = { it.path }) { entry ->
                EntryRow(entry.name, entry.deleted, entry.folder, onClick = { if (entry.folder) onFolder(entry.path) else onFile(entry.path) })
            }
        }
    }
}

@Composable
private fun EntryRow(text: String, deleted: Boolean = false, folder: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        if (deleted) Text(stringResource(R.string.preview_deleted), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
        if (folder) Text("›", style = MaterialTheme.typography.titleMedium)
    }
    HorizontalDivider()
}
