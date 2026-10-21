package com.sqftware.safegit.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.sync.ChoicePreview
import com.sqftware.safegit.sync.FileHistory
import com.sqftware.safegit.sync.FileVersions
import com.sqftware.safegit.sync.PastVersion
import kotlinx.coroutines.withContext

/** How a restore ended: as [FileHistory] answered, or failed with git's or the folder's [detail]. */
sealed interface RestoreOutcome {
    data class Ended(val restored: FileHistory.Restored) : RestoreOutcome

    data class Failed(val detail: String) : RestoreOutcome
}

/**
 * [path]'s versions, newest first, each opening into how it differs from the current one and a button to [onRestore]
 * it; [versions] is null while they load, or for good when they are [unreadable]. [outcome] is how the last restore
 * ended, shown below the list so that it is in sight wherever the list was scrolled to.
 */
@Composable
fun VersionsScreen(
    path: String,
    versions: FileVersions?,
    preview: (PastVersion) -> ChoicePreview?,
    onRestore: (PastVersion) -> Unit,
    restoring: Boolean = false,
    outcome: RestoreOutcome? = null,
    unreadable: String? = null,
) {
    Scaffold(bottomBar = { outcome?.let { Outcome(it) } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.versions_title), style = MaterialTheme.typography.headlineSmall)
                    Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
            }
            unreadable?.let { item { Text(stringResource(R.string.versions_load_failed, it), style = MaterialTheme.typography.bodyLarge) } }
            versions ?: return@LazyColumn
            if (versions.held) {
                item { Text(stringResource(R.string.versions_conflict), Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            if (versions.versions.isEmpty()) {
                item { Text(stringResource(R.string.versions_empty), style = MaterialTheme.typography.bodyLarge) }
            }
            items(versions.versions, key = { it.commit }) { version ->
                VersionCard(version, versions.isCurrent(version), restorable = versions.canRestore(version) && !restoring, preview, onRestore)
            }
        }
    }
}

@Composable
private fun Outcome(outcome: RestoreOutcome) {
    val text = when (outcome) {
        is RestoreOutcome.Failed -> stringResource(R.string.versions_failed, outcome.detail)
        is RestoreOutcome.Ended -> stringResource(
            when (outcome.restored) {
                FileHistory.Restored.DONE -> R.string.versions_restored
                FileHistory.Restored.CHANGED -> R.string.versions_changed
                FileHistory.Restored.CONFLICT -> R.string.versions_conflict
                FileHistory.Restored.IN_THE_WAY -> R.string.versions_in_the_way
            },
        )
    }
    val done = outcome == RestoreOutcome.Ended(FileHistory.Restored.DONE)
    Surface(color = if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer) {
        Text(text, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VersionCard(
    version: PastVersion,
    current: Boolean,
    restorable: Boolean,
    preview: (PastVersion) -> ChoicePreview?,
    onRestore: (PastVersion) -> Unit,
) {
    // A deletion has nothing to show or restore
    val opens = version.blob != null
    var open by rememberSaveable { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(enabled = opens) { open = !open }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(madeAt(version), style = MaterialTheme.typography.bodySmall)
                Text(version.commit.take(SHORT_SHA), Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                if (current) Text(stringResource(R.string.versions_current), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            Text(
                stringResource(R.string.versions_by, version.author, version.subject) + if (!opens) "" else if (open) " ▴" else " ▾",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!opens) Text(stringResource(R.string.preview_deleted), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            if (!open) return@Column

            // Null while it loads, and a null preview once it could not be read
            val shown by produceState<Result<ChoicePreview?>?>(null, version) {
                value = Result.success(withContext(previewing) { preview(version) })
            }
            shown?.let { loaded ->
                loaded.getOrNull()?.let { ChoicePreviewView(it, stringResource(R.string.versions_now), stringResource(R.string.versions_then)) }
                    ?: Text(stringResource(R.string.versions_unreadable), style = MaterialTheme.typography.bodySmall)
            }
            if (restorable) {
                Button(onClick = { onRestore(version) }, Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.versions_restore)) }
            }
        }
    }
}

@Composable
private fun madeAt(version: PastVersion) = DateUtils.formatDateTime(
    LocalContext.current,
    version.time,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
)

private const val SHORT_SHA = 7
