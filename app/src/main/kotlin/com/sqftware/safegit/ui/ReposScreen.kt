package com.sqftware.safegit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.Step
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.work.SyncState

/** A connected repo as the list shows it; [canSignIn] when a new sign-in could help its remote, and none is under way. */
data class RepoRow(val repo: SavedRepo, val state: SyncState?, val canSignIn: Boolean = false)

/** The connected repos, under [header], such as the account syncs run as; [onSignIn] replaces a sign-in a remote refused. */
@Composable
fun ReposScreen(
    rows: List<RepoRow>,
    onSync: (SavedRepo, Map<Choice, Keep>) -> Unit,
    onAdd: () -> Unit,
    onSignIn: () -> Unit = {},
    header: @Composable () -> Unit = {},
) {
    Scaffold(
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd) { Text(stringResource(R.string.repos_add)) } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item { header() }
            if (rows.isEmpty()) {
                item { Text(stringResource(R.string.repos_empty), Modifier.padding(8.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            items(rows, key = { it.repo.id }) { row ->
                RepoCard(row, onSync = { choices -> onSync(row.repo, choices) }, onSignIn)
            }
        }
    }
}

@Composable
private fun RepoCard(row: RepoRow, onSync: (Map<Choice, Keep>) -> Unit, onSignIn: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.repo.name, style = MaterialTheme.typography.titleMedium)
            Text(row.repo.url, style = MaterialTheme.typography.bodySmall)

            when (val state = row.state) {
                null -> Text(stringResource(R.string.state_never))
                SyncState.Waiting, is SyncState.Running -> {
                    Text(stringResource(if (state is SyncState.Running) stepText(state.step) else R.string.state_waiting))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is SyncState.Finished -> Outcome(state.result, onSync, row.canSignIn, onSignIn)
            }

            // A choice syncs with its own button
            val choosing = (row.state as? SyncState.Finished)?.result is SyncResult.Choose
            if (!choosing) {
                Button(onClick = { onSync(emptyMap()) }, enabled = row.state == null || row.state is SyncState.Finished) { Text(stringResource(R.string.sync)) }
            }
        }
    }
}

@Composable
private fun Outcome(result: SyncResult, onSync: (Map<Choice, Keep>) -> Unit, canSignIn: Boolean, onSignIn: () -> Unit) {
    when (result) {
        SyncResult.UpToDate -> Text(stringResource(R.string.state_up_to_date))
        is SyncResult.Synced -> Text(stringResource(R.string.state_synced))
        is SyncResult.Conflict -> {
            Text(stringResource(R.string.state_conflict))
            Column {
                for (path in result.paths) Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
        }
        is SyncResult.Choose -> Choices(result.choices, onSync)
        is SyncResult.Failed -> {
            Failure(result.problem, result.detail)
            if (result.problem == Problem.SIGN_IN && canSignIn) OutlinedButton(onClick = onSignIn) { Text(stringResource(R.string.sign_in_again)) }
        }
    }
}

/** Asks for a [Keep] for each choice, and syncs with them once every one has one. */
@Composable
private fun Choices(choices: List<Choice>, onSync: (Map<Choice, Keep>) -> Unit) {
    val kept = remember(choices) { mutableStateMapOf<Choice, Keep>() }
    Text(stringResource(R.string.state_choose))
    for (choice in choices) {
        Column {
            Text(choice.path, fontFamily = FontFamily.Monospace)
            Text(sides(choice), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(kept[choice] == Keep.LOCAL, onClick = { kept[choice] = Keep.LOCAL }, label = { Text(stringResource(R.string.keep_local)) })
                FilterChip(kept[choice] == Keep.REMOTE, onClick = { kept[choice] = Keep.REMOTE }, label = { Text(stringResource(R.string.keep_remote)) })
            }
        }
    }
    Button(onClick = { onSync(kept.toMap()) }, enabled = kept.size == choices.size) { Text(stringResource(R.string.sync_with_choices)) }
}

@Composable
private fun sides(choice: Choice) = when {
    choice.local != null && choice.remote != null -> stringResource(R.string.choice_both)
    else -> stringResource(R.string.choice_sides, side(choice.local), side(choice.remote))
}

@Composable
private fun side(objectId: String?) = stringResource(if (objectId == null) R.string.side_none else R.string.side_file)

private fun stepText(step: Step?) = when (step) {
    null -> R.string.state_running
    Step.COMMIT -> R.string.step_commit
    Step.FETCH -> R.string.step_fetch
    Step.MERGE -> R.string.step_merge
    Step.UPDATE_FOLDER -> R.string.step_update_folder
    Step.PUSH -> R.string.step_push
}
