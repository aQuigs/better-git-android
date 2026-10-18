package com.sqftware.safegit.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.sync.FileChanges

/** How many files [files] changes of each kind, such as "2 changed, 1 new". */
@Composable
fun fileCounts(files: FileChanges): String =
    kinds(files).map { pluralStringResource(it.count, it.paths.size, it.paths.size) }.joinToString(", ")

/** Each of [files] with what happened to it, each opening with [onFile] where given. */
@Composable
fun FileList(files: FileChanges, onFile: ((String) -> Unit)? = null) {
    val kinds = kinds(files)
    // A first sync of a vault can hold thousands of files, too many to lay out at once
    val shown = kinds.asSequence().flatMap { kind -> kind.paths.asSequence().map { it to kind.label } }.take(MAX_FILES).toList()
    Column {
        for ((path, label) in shown) {
            Row(Modifier.opens(path, onFile), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = pathColor(onFile))
                Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
            }
        }
        val more = files.size - shown.size
        if (more > 0) Text(pluralStringResource(R.plurals.pending_more, more, more), style = MaterialTheme.typography.bodySmall)
    }
}

/** Paths, one to a line, each opening with [onFile] where given and followed by its [action], if any. */
@Composable
fun PathList(paths: List<String>, onFile: ((String) -> Unit)? = null, action: @Composable (String) -> Unit = {}) {
    Column {
        for (path in paths) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(path, Modifier.weight(1f).opens(path, onFile), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = pathColor(onFile))
                action(path)
            }
        }
    }
}

private fun Modifier.opens(path: String, onFile: ((String) -> Unit)?) =
    if (onFile == null) this else fillMaxWidth().clickable { onFile(path) }.padding(vertical = 4.dp)

@Composable
private fun pathColor(onFile: ((String) -> Unit)?) = if (onFile == null) Color.Unspecified else MaterialTheme.colorScheme.primary

private fun kinds(files: FileChanges) = listOf(
    Kind(R.plurals.pending_changed, R.string.pending_changed_file, files.changed),
    Kind(R.plurals.pending_new, R.string.pending_new_file, files.added),
    Kind(R.plurals.pending_deleted, R.string.pending_deleted_file, files.deleted),
).filter { it.paths.isNotEmpty() }

/** One kind of change: the [count] line's part, and the [label] beside each of its [paths]. */
private class Kind(@PluralsRes val count: Int, @StringRes val label: Int, val paths: List<String>)

private const val MAX_FILES = 100
