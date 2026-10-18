package com.sqftware.safegit.ui

import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.sync.ChoicePreview
import com.sqftware.safegit.sync.DiffLine
import com.sqftware.safegit.sync.Keep
import com.sqftware.safegit.sync.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What each side of a choice holds, side by side, over how the two differ where both are text. */
@Composable
fun ChoicePreviewView(preview: ChoicePreview) {
    val diffed = preview.diff.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SideView(stringResource(R.string.preview_phone), sideColor(Keep.LOCAL), preview.local, diffed, Modifier.weight(1f))
            SideView(stringResource(R.string.preview_remote), sideColor(Keep.REMOTE), preview.remote, diffed, Modifier.weight(1f))
        }
        if (diffed) Diff(preview.diff)
    }
}

/** One side's version under its name, tinted as its lines are in the diff. With [diffed], its text shows there instead. */
@Composable
private fun SideView(name: String, color: Color, version: Version, diffed: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, Modifier.background(color, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium)
        val small = MaterialTheme.typography.bodySmall
        when (version) {
            Version.Deleted -> Text(stringResource(R.string.preview_deleted), style = small, fontStyle = FontStyle.Italic)
            is Version.Folder -> Text(pluralStringResource(R.plurals.preview_folder, version.files, version.files), style = small)
            is Version.Binary -> Text(stringResource(R.string.preview_file, size(version.size)), style = small)
            is Version.Elsewhere -> Text(stringResource(R.string.preview_elsewhere, version.id.take(SHORT_ID)), style = small)
            is Version.Image -> Thumbnail(name, version.bytes)
            is Version.Text -> if (diffed) {
                Text(pluralStringResource(R.plurals.preview_lines, version.total, version.total), style = small)
            } else {
                Snippet(version)
            }
        }
    }
}

@Composable
private fun Snippet(text: Version.Text) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
        Column(Modifier.padding(4.dp)) {
            for (line in text.lines) Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            val more = text.total - text.lines.size
            if (more > 0) Text(pluralStringResource(R.plurals.preview_more_lines, more, more), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Lines in both sides plain, each side's own tinted as its name is, and "⋯" where lines are left out. */
@Composable
private fun Diff(diff: List<DiffLine>) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.extraSmall) {
        Column(Modifier.fillMaxWidth().heightIn(max = DIFF_HEIGHT.dp).verticalScroll(rememberScrollState()).padding(4.dp)) {
            for (line in diff) {
                val tint = line.side?.let { Modifier.background(sideColor(it)) } ?: Modifier
                Text(line.text ?: "⋯", tint.fillMaxWidth(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Decoded small, off the main thread, since a photo can be many megabytes; one that will not decode shows its size. */
@Composable
private fun Thumbnail(name: String, bytes: ByteArray) {
    val image by produceState<ImageBitmap?>(null, bytes) { value = withContext(Dispatchers.Default) { decode(bytes) } }
    val decoded = image
    if (decoded == null) {
        Text(stringResource(R.string.preview_file, size(bytes.size.toLong())), style = MaterialTheme.typography.bodySmall)
    } else {
        Image(decoded, stringResource(R.string.preview_image, name), Modifier.size(THUMBNAIL.dp), contentScale = ContentScale.Fit)
    }
}

private fun decode(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PIXELS) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

@Composable
private fun size(bytes: Long) = Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
private fun sideColor(side: Keep) = if (side == Keep.LOCAL) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer

private const val SHORT_ID = 7
private const val THUMBNAIL = 96
private const val THUMBNAIL_PIXELS = 384
private const val DIFF_HEIGHT = 240
