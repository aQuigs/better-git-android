package com.sqftware.safegit.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.repos.AutoSync
import com.sqftware.safegit.repos.SavedRepo
import com.sqftware.safegit.sync.Choice
import com.sqftware.safegit.sync.ChoicePreview
import com.sqftware.safegit.sync.CommitFacts
import com.sqftware.safegit.sync.CommitTemplate
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.Pending
import com.sqftware.safegit.sync.Problem
import com.sqftware.safegit.sync.Step
import com.sqftware.safegit.sync.SyncResult
import com.sqftware.safegit.work.AllRun
import com.sqftware.safegit.work.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A connected repo as the list shows it; [canSignIn] when a new sign-in could help its remote, and none is under way.
 * [pending] is what its next sync would push, when known.
 */
data class RepoRow(val repo: SavedRepo, val state: SyncState?, val canSignIn: Boolean = false, val pending: Pending? = null)

/**
 * The connected repos, under [header], such as the account syncs run as; [onSignIn] replaces a sign-in a remote refused.
 * With more than one, they can all be synced at once; [all] is the last such sync. [preview] reads what each side of a
 * choice holds, off the main thread, or null when it cannot.
 */
@Composable
fun ReposScreen(
    rows: List<RepoRow>,
    onSync: (SavedRepo, Map<Choice, Keep>) -> Unit,
    onAdd: () -> Unit,
    onCancel: (SavedRepo) -> Unit = {},
    onSignIn: () -> Unit = {},
    onAuto: (SavedRepo, AutoSync) -> Unit = { _, _ -> },
    onMessage: (SavedRepo, String) -> Unit = { _, _ -> },
    device: String = "",
    onAbout: () -> Unit = {},
    all: AllRun? = null,
    onSyncAll: () -> Unit = {},
    onCancelAll: () -> Unit = {},
    onCloseAll: () -> Unit = {},
    preview: (SavedRepo, Choice) -> ChoicePreview? = { _, _ -> null },
    header: @Composable () -> Unit = {},
) {
    Scaffold(
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd) { Text(stringResource(R.string.repos_add)) } },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item { header() }
            if (rows.size > 1) item { SyncAllCard(rows, all, onSyncAll, onCancelAll, onCloseAll) }
            if (rows.isEmpty()) {
                item { Text(stringResource(R.string.repos_empty), Modifier.padding(8.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            items(rows, key = { it.repo.id }) { row ->
                RepoCard(
                    row,
                    onSync = { choices -> onSync(row.repo, choices) },
                    onCancel = { onCancel(row.repo) },
                    onSignIn,
                    onAuto = { onAuto(row.repo, it) },
                    onMessage = { onMessage(row.repo, it) },
                    device,
                    preview = { preview(row.repo, it) },
                )
            }
            item { TextButton(onClick = onAbout) { Text(stringResource(R.string.about_open)) } }
        }
    }
}

@Composable
private fun RepoCard(
    row: RepoRow,
    onSync: (Map<Choice, Keep>) -> Unit,
    onCancel: () -> Unit,
    onSignIn: () -> Unit,
    onAuto: (AutoSync) -> Unit,
    onMessage: (String) -> Unit,
    device: String,
    preview: (Choice) -> ChoicePreview?,
) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Box(Modifier.weight(1f)) { RepoTitle(row.repo) }
                RepoMenu(row.repo.message, onMessage, device)
            }
            // Here rather than with the choices, so picks made outlast a sync that runs meanwhile, such as an automatic one
            val kept = remember(row.repo.id) { mutableStateMapOf<Choice, Keep>() }

            when (val state = row.state) {
                null -> Text(stringResource(R.string.state_never))
                SyncState.Waiting, is SyncState.Running -> {
                    Text(stringResource(if (state is SyncState.Running) runningText(state) else R.string.state_waiting))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is SyncState.Finished -> Outcome(state.result, kept, onSync, row.canSignIn, onSignIn, preview)
            }
            row.pending?.let { PendingFiles(it) }

            // A choice syncs with its own button
            val choosing = (row.state as? SyncState.Finished)?.result is SyncResult.Choose
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val running = row.state as? SyncState.Running
                if (running != null) {
                    OutlinedButton(onClick = onCancel, enabled = !running.cancelling) { Text(stringResource(R.string.cancel)) }
                } else if (!choosing) {
                    Button(onClick = { onSync(emptyMap()) }, enabled = row.state != SyncState.Waiting) { Text(stringResource(R.string.sync)) }
                }
                AutoSyncPicker(row.repo.auto, onAuto)
            }
        }
    }
}

/** The repo's name with its branch as a tag, over its URL. */
@Composable
private fun RepoTitle(repo: SavedRepo) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(repo.name, style = MaterialTheme.typography.titleMedium)
            if (repo.branch.isNotEmpty()) {
                val description = stringResource(R.string.repo_branch, repo.branch)
                Surface(Modifier.semantics { contentDescription = description }, MaterialTheme.shapes.small, MaterialTheme.colorScheme.secondaryContainer) {
                    Text(repo.branch, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(repo.url, style = MaterialTheme.typography.bodySmall)
    }
}

/** A count of the files the next sync would push, which opens into the files themselves. */
@Composable
private fun PendingFiles(pending: Pending) {
    if (pending.isEmpty()) {
        Text(stringResource(R.string.pending_none), style = MaterialTheme.typography.bodyMedium)
        return
    }

    var open by rememberSaveable { mutableStateOf(false) }
    val kinds = listOf(
        Kind(R.plurals.pending_changed, R.string.pending_changed_file, pending.changed),
        Kind(R.plurals.pending_new, R.string.pending_new_file, pending.added),
        Kind(R.plurals.pending_deleted, R.string.pending_deleted_file, pending.deleted),
    ).filter { it.paths.isNotEmpty() }
    val counts = kinds.map { pluralStringResource(it.count, it.paths.size, it.paths.size) }.joinToString(", ")
    Text(
        stringResource(R.string.pending_count, counts) + if (open) " ▴" else " ▾",
        Modifier.clickable { open = !open },
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!open) return

    // A first sync of a whole vault can hold thousands of files, too many to lay out in a card
    val files = kinds.asSequence().flatMap { kind -> kind.paths.asSequence().map { it to kind.label } }.take(MAX_FILES).toList()
    Column {
        for ((path, label) in files) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
            }
        }
        val more = kinds.sumOf { it.paths.size } - files.size
        if (more > 0) Text(pluralStringResource(R.plurals.pending_more, more, more), style = MaterialTheme.typography.bodySmall)
    }
}

/** One kind of change: the [count] line's part, and the [label] beside each of its [paths]. */
private class Kind(@PluralsRes val count: Int, @StringRes val label: Int, val paths: List<String>)

private const val MAX_FILES = 100
private const val EXAMPLE_FILES = 3

/**
 * The repo's settings that are seldom changed, kept off the card: for now its commit message template, edited with an
 * example of what it writes on [device].
 */
@Composable
private fun RepoMenu(message: String, onMessage: (String) -> Unit, device: String) {
    var open by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.repo_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.commit_message)) },
                onClick = {
                    open = false
                    draft = message
                },
            )
        }
    }

    val editing = draft ?: return
    AlertDialog(
        onDismissRequest = { draft = null },
        title = { Text(stringResource(R.string.commit_message)) },
        text = {
            OutlinedTextField(
                editing,
                onValueChange = { draft = it },
                Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.commit_message_label)) },
                singleLine = true,
                supportingText = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val example = CommitTemplate(editing).write(CommitFacts(device, System.currentTimeMillis()) { EXAMPLE_FILES })
                        Text(stringResource(R.string.commit_message_example, example))
                        Text(stringResource(R.string.commit_message_help))
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                draft = null
                val saved = editing.trim().ifEmpty { CommitTemplate.DEFAULT_TEMPLATE }
                if (saved != message) onMessage(saved)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = { draft = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun AutoSyncPicker(auto: AutoSync, onPick: (AutoSync) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.auto_sync, stringResource(autoText(auto)))) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (option in AutoSync.entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(autoText(option))) },
                    onClick = {
                        open = false
                        if (option != auto) onPick(option)
                    },
                )
            }
        }
    }
}

private fun autoText(auto: AutoSync) = when (auto) {
    AutoSync.OFF -> R.string.auto_off
    AutoSync.EVERY_15_MINUTES -> R.string.auto_15_minutes
    AutoSync.HOURLY -> R.string.auto_hourly
    AutoSync.EVERY_6_HOURS -> R.string.auto_6_hours
    AutoSync.DAILY -> R.string.auto_daily
    AutoSync.WEEKLY -> R.string.auto_weekly
}

@Composable
private fun Outcome(
    result: SyncResult,
    kept: MutableMap<Choice, Keep>,
    onSync: (Map<Choice, Keep>) -> Unit,
    canSignIn: Boolean,
    onSignIn: () -> Unit,
    preview: (Choice) -> ChoicePreview?,
) {
    when (result) {
        SyncResult.UpToDate -> Text(stringResource(R.string.state_up_to_date))
        is SyncResult.Synced -> Text(stringResource(R.string.state_synced))
        is SyncResult.Conflict -> Paths(stringResource(R.string.state_conflict), result.paths)
        is SyncResult.ConflictAhead -> Paths(stringResource(R.string.state_conflict_ahead), result.paths)
        is SyncResult.Choose -> Choices(result.choices, kept, onSync, preview)
        is SyncResult.Failed -> {
            Failure(result.problem, result.detail)
            if (result.problem == Problem.SIGN_IN && canSignIn) OutlinedButton(onClick = onSignIn) { Text(stringResource(R.string.sign_in_again)) }
        }
        SyncResult.Cancelled -> Text(stringResource(R.string.state_cancelled))
    }
}

@Composable
private fun Paths(explanation: String, paths: List<String>) {
    Text(explanation)
    Column {
        for (path in paths) Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Asks for a [Keep] for each choice, showing what each side holds, and syncs with them once every one has one. */
@Composable
private fun Choices(choices: List<Choice>, kept: MutableMap<Choice, Keep>, onSync: (Map<Choice, Keep>) -> Unit, preview: (Choice) -> ChoicePreview?) {
    Text(stringResource(R.string.state_choose))
    for (choice in choices) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(choice.path, fontFamily = FontFamily.Monospace)
            val shown by produceState<ChoicePreview?>(null, choice) { value = withContext(Dispatchers.IO) { preview(choice) } }
            shown?.let { ChoicePreviewView(it) } ?: Text(sides(choice), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(kept[choice] == Keep.LOCAL, onClick = { kept[choice] = Keep.LOCAL }, label = { Text(stringResource(R.string.keep_local)) })
                FilterChip(kept[choice] == Keep.REMOTE, onClick = { kept[choice] = Keep.REMOTE }, label = { Text(stringResource(R.string.keep_remote)) })
            }
        }
    }
    // Picks for choices an earlier sync asked are left out, as each counts only against what it was made for
    Button(onClick = { onSync(kept.filterKeys { it in choices }) }, enabled = choices.all { it in kept }) { Text(stringResource(R.string.sync_with_choices)) }
}

@Composable
private fun sides(choice: Choice) = when {
    choice.local != null && choice.remote != null -> stringResource(R.string.choice_both)
    else -> stringResource(R.string.choice_sides, side(choice.local), side(choice.remote))
}

@Composable
private fun side(objectId: String?) = stringResource(if (objectId == null) R.string.side_none else R.string.side_file)

private fun runningText(state: SyncState.Running) = if (state.cancelling) R.string.state_cancelling else when (state.step) {
    null -> R.string.state_running
    Step.COMMIT -> R.string.step_commit
    Step.FETCH -> R.string.step_fetch
    Step.MERGE -> R.string.step_merge
    Step.UPDATE_FOLDER -> R.string.step_update_folder
    Step.PUSH -> R.string.step_push
}
