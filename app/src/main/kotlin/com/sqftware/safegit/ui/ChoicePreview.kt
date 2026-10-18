package com.sqftware.safegit.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/** What each side holds under its name, side by side, over how the two differ where both are text. */
@Composable
fun ChoicePreviewView(
    preview: ChoicePreview,
    localName: String = stringResource(R.string.preview_phone),
    remoteName: String = stringResource(R.string.preview_remote),
) {
    val diffed = preview.diff.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SideView(localName, sideColor(Keep.LOCAL), preview.local, diffed, Modifier.weight(1f))
            SideView(remoteName, sideColor(Keep.REMOTE), preview.remote, diffed, Modifier.weight(1f))
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
            is Version.Image -> Thumbnail(name, version)
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

/**
 * Read and decoded off the main thread, keeping only a thumbnail, since a photo can be many megabytes and a choice can
 * hold many; one that will not decode shows its size.
 */
@Composable
private fun Thumbnail(name: String, image: Version.Image) {
    val thumbnail by produceState<Thumbnail>(Thumbnail.Loading, image) { value = withContext(previewing) { thumbnail(image) } }
    when (val shown = thumbnail) {
        Thumbnail.Loading -> Box(Modifier.size(THUMBNAIL.dp))
        Thumbnail.Unreadable -> Text(stringResource(R.string.preview_file, size(image.size)), style = MaterialTheme.typography.bodySmall)
        is Thumbnail.Shown -> Image(shown.bitmap, stringResource(R.string.preview_image, name), Modifier.size(THUMBNAIL.dp), contentScale = ContentScale.Fit)
    }
}

private sealed interface Thumbnail {
    data object Loading : Thumbnail

    data object Unreadable : Thumbnail

    class Shown(val bitmap: ImageBitmap) : Thumbnail
}

private fun thumbnail(image: Version.Image): Thumbnail = try {
    val bytes = image.read()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_PIXELS) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    decoded?.let { Thumbnail.Shown(scaled(it).asImageBitmap()) } ?: Thumbnail.Unreadable
} catch (_: Exception) {
    Thumbnail.Unreadable
} catch (_: OutOfMemoryError) {
    // A huge image that a busy app cannot decode is shown by its size, rather than taking the app down
    Thumbnail.Unreadable
}

/** Down to the thumbnail's own size: sampling alone leaves up to twice it on a side. */
private fun scaled(bitmap: Bitmap): Bitmap {
    val scale = THUMBNAIL_PIXELS.toFloat() / maxOf(bitmap.width, bitmap.height)
    if (scale >= 1) return bitmap
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
}

/** Previews and thumbnails load two at a time, so a choice of many files never runs a git or holds a photo for each at once. */
internal val previewing = Dispatchers.IO.limitedParallelism(2)

@Composable
private fun size(bytes: Long) = Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
internal fun sideColor(side: Keep) = if (side == Keep.LOCAL) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer

private const val SHORT_ID = 7
private const val THUMBNAIL = 96
private const val THUMBNAIL_PIXELS = 288
private const val DIFF_HEIGHT = 240
