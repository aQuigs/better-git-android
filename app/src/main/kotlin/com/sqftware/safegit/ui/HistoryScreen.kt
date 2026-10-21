package com.sqftware.safegit.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.sync.FileChanges
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.work.RecordedSync

/**
 * [name]'s last syncs, newest first, each opening into the files it moved; [syncs] is null while they load. A file
 * opens its versions with [onFile].
 */
@Composable
fun HistoryScreen(name: String, syncs: List<RecordedSync>?, onFile: (String) -> Unit = {}) {
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall)
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (syncs?.isEmpty() == true) {
                item { Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge) }
            }
            // Two syncs of a repo never end in the same millisecond, as they never overlap
            items(syncs.orEmpty(), key = { it.at }) { PastSyncCard(it, onFile) }
        }
    }
}

@Composable
private fun PastSyncCard(sync: RecordedSync, onFile: (String) -> Unit) {
    val (pulled, pushed) = sync.transfer
    val paths = when (val result = sync.result) {
        is SyncResult.Conflict -> result.paths
        is SyncResult.ConflictAhead -> result.paths
        is SyncResult.Choose -> result.choices.map { it.path }
        else -> emptyList()
    }
    val opens = !pulled.isEmpty() || !pushed.isEmpty() || paths.isNotEmpty() || sync.result is SyncResult.Failed
    var open by rememberSaveable { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(enabled = opens) { open = !open }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(endedAt(sync), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                ShortOutcome(sync.result)
            }
            val moved = listOfNotNull(
                pulled.takeUnless(FileChanges::isEmpty)?.let { stringResource(R.string.history_pulled, fileCounts(it)) },
                pushed.takeUnless(FileChanges::isEmpty)?.let { stringResource(R.string.history_pushed, fileCounts(it)) },
            ).ifEmpty { listOf(stringResource(R.string.history_nothing_moved)) }
            Text(moved.joinToString("\n") + if (!opens) "" else if (open) " ▴" else " ▾", style = MaterialTheme.typography.bodyMedium)
            if (!open) return@Column

            (sync.result as? SyncResult.Failed)?.let { Failure(it.problem, it.detail) }
            if (paths.isNotEmpty()) {
                Section(stringResource(R.string.history_conflicts))
                PathList(paths, onFile)
            }
            if (!pulled.isEmpty()) {
                Section(stringResource(R.string.history_pulled_files))
                FileList(pulled, onFile)
            }
            if (!pushed.isEmpty()) {
                Section(stringResource(R.string.history_pushed_files))
                FileList(pushed, onFile)
            }
        }
    }
}

@Composable
private fun Section(text: String) = Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)

@Composable
private fun endedAt(sync: RecordedSync): String {
    val time = DateUtils.formatDateTime(
        LocalContext.current,
        sync.at,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
    return if (sync.automatic) stringResource(R.string.history_automatic, time) else time
}
