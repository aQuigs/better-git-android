package com.sqftware.safegit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.MarkedFile
import com.sqftware.safegit.sync.MarkedFiles.Saved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Each conflict in the file at [path] with both sides next to each other, to keep one, both or an edit of them. [open]
 * reads the file and [save] writes it, both off the main thread; [open] gives null for a file that cannot be edited here.
 * Blocks left alone keep their markers. [onDone] returns to the repos, also once the file is saved.
 */
@Composable
fun ResolveScreen(path: String, open: () -> MarkedFile?, save: (MarkedFile, String) -> Saved, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    // Bumped to read the file again after it changed under a save
    var opened by rememberSaveable { mutableIntStateOf(0) }
    // By block, so a rotation or a new read of the file keeps the picks for blocks still there
    val picks = rememberSaveable(saver = PicksSaver) { mutableStateMapOf() }
    val file by produceState<Opening>(Opening.Loading, opened) {
        value = Opening.Loading
        value = withContext(Dispatchers.IO) { open() }?.let(Opening::Shown) ?: Opening.Unreadable
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Text(path, Modifier.padding(vertical = 12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            when (val shown = file) {
                Opening.Loading -> CircularProgressIndicator()
                Opening.Unreadable -> Note(stringResource(R.string.resolve_unreadable), onDone)
                is Opening.Shown -> if (shown.file.blocks.isEmpty()) {
                    Note(stringResource(if (shown.file.stray) R.string.resolve_stray else R.string.resolve_none), onDone)
                } else {
                    // A refusal was of the file as it was
                    key(opened) { Blocks(shown.file, picks, save, onReopen = { opened++ }, onDone) }
                }
            }
        }
    }
}

private sealed interface Opening {
    data object Loading : Opening

    data object Unreadable : Opening

    class Shown(val file: MarkedFile) : Opening
}

private enum class Kept { LOCAL, REMOTE, BOTH, EDIT }

/** What a block becomes; [edit] is the user's text for [Kept.EDIT]. */
private data class Pick(val kept: Kept, val edit: String = "") {
    fun text(block: MarkedFile.Part.Block) = when (kept) {
        Kept.LOCAL -> block.local
        Kept.REMOTE -> block.remote
        Kept.BOTH -> block.both
        Kept.EDIT -> edit
    }
}

/**
 * A block by its two sides, hashed so that the picks fit in saved state however large the file, and counted among
 * blocks alike.
 */
private data class BlockKey(val sides: Int, val nth: Int)

private fun keys(file: MarkedFile): List<BlockKey> {
    val seen = HashMap<Int, Int>()
    return file.blocks.map { block ->
        val sides = (block.local + '\u0000' + block.remote).hashCode()
        BlockKey(sides, seen.merge(sides, 1, Int::plus)!!)
    }
}

private val PicksSaver = listSaver<SnapshotStateMap<BlockKey, Pick>, String>(
    save = { picks -> picks.flatMap { (key, pick) -> listOf(key.sides.toString(), key.nth.toString(), pick.kept.name, pick.edit) } },
    restore = { saved ->
        mutableStateMapOf<BlockKey, Pick>().apply {
            saved.chunked(4).forEach { (sides, nth, kept, edit) -> put(BlockKey(sides.toInt(), nth.toInt()), Pick(Kept.valueOf(kept), edit)) }
        }
    },
)

@Composable
private fun Note(text: String, onDone: () -> Unit) {
    Text(text)
    TextButton(onClick = onDone) { Text(stringResource(R.string.resolve_back)) }
}

@Composable
private fun Blocks(file: MarkedFile, picks: MutableMap<BlockKey, Pick>, save: (MarkedFile, String) -> Saved, onReopen: () -> Unit, onDone: () -> Unit) {
    val keys = remember(file) { keys(file) }
    val contexts = remember(file) { contexts(file) }
    val picked = keys.count { it in picks }
    var saving by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<Saved?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        if (file.stray) Text(stringResource(R.string.resolve_stray), style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val blocks = file.blocks
            items(blocks.size) { index ->
                BlockCard(index, blocks.size, blocks[index], contexts[index], picks[keys[index]], onPick = { picks[keys[index]] = it })
            }
        }

        when (val problem = refused) {
            Saved.Changed -> {
                Text(stringResource(R.string.resolve_changed), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onReopen) { Text(stringResource(R.string.resolve_reopen)) }
            }
            is Saved.Failed -> Text(stringResource(R.string.resolve_failed, problem.detail), color = MaterialTheme.colorScheme.error)
            else -> {}
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.resolve_cancel)) }
            Button(
                onClick = {
                    saving = true
                    val resolved = keys.withIndex().mapNotNull { (index, key) -> picks[key]?.let { index to it.text(file.blocks[index]) } }.toMap()
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { save(file, file.text(resolved)) }
                        saving = false
                        if (saved == Saved.Done) onDone() else refused = saved
                    }
                },
                enabled = picked > 0 && !saving && refused != Saved.Changed,
            ) {
                Text(stringResource(if (picked == keys.size) R.string.resolve_save else R.string.resolve_save_some))
            }
        }
    }
}

/** For each block, the lines just before and after it that both sides agree on, so the user can tell where it is. */
private fun contexts(file: MarkedFile): List<Pair<String, String>> {
    // Only the near end of the text around a block is read, as it can be most of a large file
    fun same(index: Int, end: Boolean): List<String> {
        val text = (file.parts.getOrNull(index) as? MarkedFile.Part.Same)?.text.orEmpty()
        return (if (end) text.takeLast(CONTEXT_CHARS) else text.take(CONTEXT_CHARS)).lines()
    }
    fun shown(lines: List<String>) = lines.dropWhile(String::isBlank).dropLastWhile(String::isBlank).joinToString("\n")
    return file.parts.withIndex().filter { it.value is MarkedFile.Part.Block }.map { (at) ->
        shown(same(at - 1, end = true).dropLastWhile(String::isBlank).takeLast(CONTEXT)) to shown(same(at + 1, end = false).dropWhile(String::isBlank).take(CONTEXT))
    }
}

@Composable
private fun BlockCard(index: Int, count: Int, block: MarkedFile.Part.Block, context: Pair<String, String>, pick: Pick?, onPick: (Pick) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.resolve_block, index + 1, count), style = MaterialTheme.typography.labelLarge)
            Context(context.first)
            val phone = stringResource(R.string.preview_phone)
            val remote = stringResource(R.string.preview_remote)
            // A long side reads badly in half the width, so long blocks stack their sides
            if (lineCount(block.local) <= SHOWN_LINES && lineCount(block.remote) <= SHOWN_LINES) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Side(phone, sideColor(Keep.LOCAL), block.local, Modifier.weight(1f))
                    Side(remote, sideColor(Keep.REMOTE), block.remote, Modifier.weight(1f))
                }
            } else {
                Side(phone, sideColor(Keep.LOCAL), block.local, Modifier.fillMaxWidth())
                Side(remote, sideColor(Keep.REMOTE), block.remote, Modifier.fillMaxWidth())
            }
            Context(context.second)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickChip(R.string.keep_local, pick?.kept == Kept.LOCAL) { onPick(Pick(Kept.LOCAL)) }
                PickChip(R.string.keep_remote, pick?.kept == Kept.REMOTE) { onPick(Pick(Kept.REMOTE)) }
                PickChip(R.string.keep_both, pick?.kept == Kept.BOTH) { onPick(Pick(Kept.BOTH)) }
                // An edit starts from what was picked, or else both sides, to cut down
                PickChip(R.string.resolve_edit, pick?.kept == Kept.EDIT) {
                    if (pick?.kept != Kept.EDIT) onPick(Pick(Kept.EDIT, pick?.text(block) ?: block.both))
                }
            }
            if (pick?.kept == Kept.EDIT) {
                OutlinedTextField(
                    pick.edit,
                    onValueChange = { onPick(Pick(Kept.EDIT, it)) },
                    Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    label = { Text(stringResource(R.string.resolve_edit_label)) },
                )
            }
        }
    }
}

@Composable
private fun PickChip(label: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick = onClick, label = { Text(stringResource(label)) })
}

@Composable
private fun Context(text: String) {
    if (text.isEmpty()) return
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun lineCount(text: String) = text.count { it == '\n' }

/** One side's lines under its name, tinted as the choice preview tints that side, its first ones until the user asks for all. */
@Composable
private fun Side(name: String, color: Color, text: String, modifier: Modifier) {
    var all by rememberSaveable { mutableStateOf(false) }
    val lines = lineCount(text)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.labelMedium)
        Surface(Modifier.fillMaxWidth(), color = color, shape = MaterialTheme.shapes.extraSmall) {
            if (text.isEmpty()) {
                Text(stringResource(R.string.resolve_no_lines), Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            } else {
                val shown = if (all || lines <= COLLAPSED_OVER) text else text.lineSequence().take(SHOWN_LINES).joinToString("\n")
                Text(shown.removeSuffix("\n"), Modifier.padding(4.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (lines > COLLAPSED_OVER) {
            TextButton(onClick = { all = !all }) {
                Text(if (all) stringResource(R.string.resolve_show_fewer) else pluralStringResource(R.plurals.resolve_show_all, lines, lines))
            }
        }
    }
}

private const val CONTEXT = 2
private const val SHOWN_LINES = 12

// Cutting a few lines short would save less room than its button takes
private const val COLLAPSED_OVER = 16
private const val CONTEXT_CHARS = 1000
