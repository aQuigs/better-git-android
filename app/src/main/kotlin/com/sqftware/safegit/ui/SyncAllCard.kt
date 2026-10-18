package com.sqftware.safegit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.work.AllRun
import com.sqftware.safegit.work.SyncState

/** Syncs every repo, one after another, and sums up [all], the last such sync, with the repos named as in [rows]. */
@Composable
fun SyncAllCard(rows: List<RepoRow>, all: AllRun?, onSyncAll: () -> Unit, onCancel: () -> Unit, onClose: () -> Unit) {
    val going = all?.going == true

    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (all != null) {
                val run = all.ids.mapNotNull { id -> rows.find { it.repo.id == id }?.let { it to all.results[id] } }
                val done = run.count { (_, result) -> result != null }
                val needed = run.count { (_, result) -> result?.done != true }
                val heading = when {
                    going -> stringResource(R.string.all_running, done, run.size)
                    needed == 0 -> stringResource(R.string.all_fine)
                    else -> pluralStringResource(R.plurals.all_needed, needed, needed, run.size)
                }
                Text(heading, style = MaterialTheme.typography.titleMedium)
                if (going) LinearProgressIndicator({ done.toFloat() / run.size }, Modifier.fillMaxWidth())
                for ((row, result) in run) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.repo.name, Modifier.weight(1f))
                        RunOutcome(result, going, running = row.state is SyncState.Running)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSyncAll, enabled = !going) { Text(stringResource(R.string.sync_all)) }
                if (going) OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                if (all != null && !going) TextButton(onClick = onClose) { Text(stringResource(R.string.all_close)) }
            }
        }
    }
}

/** A repo's result in the run, or while it has none, whether it is still to come. */
@Composable
private fun RunOutcome(result: SyncResult?, going: Boolean, running: Boolean) {
    val text = when (result) {
        SyncResult.UpToDate -> R.string.state_up_to_date
        is SyncResult.Synced -> R.string.state_synced
        is SyncResult.Conflict, is SyncResult.ConflictAhead -> R.string.all_conflict
        is SyncResult.Choose -> R.string.all_choose
        is SyncResult.Failed -> R.string.all_failed
        SyncResult.Cancelled -> R.string.all_cancelled
        null -> when {
            !going -> R.string.all_not_run
            running -> R.string.state_running
            else -> R.string.all_waiting
        }
    }
    Text(stringResource(text), color = if (result is SyncResult.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}
