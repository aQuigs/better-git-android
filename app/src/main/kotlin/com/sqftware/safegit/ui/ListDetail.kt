package com.sqftware.safegit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R

/** Material's medium width class, which an unfolded foldable reaches in either orientation */
private val TWO_PANES = 600.dp

/**
 * [list] beside [detail] on a wide window, such as a tablet's or an unfolded foldable's; on a narrow one, [detail]
 * when there is one, in place of [list].
 */
@Composable
fun ListDetail(list: @Composable () -> Unit, detail: (@Composable () -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= TWO_PANES
        val listWidth = (maxWidth * 0.4f).coerceIn(320.dp, 480.dp)
        // Each pane sits at the same place in the composition in either layout, so what it saved, such as a search or
        // where a list was scrolled, comes back when a fold or unfold recreates the activity
        Row(Modifier.fillMaxSize()) {
            if (wide || detail == null) {
                Box(if (wide) Modifier.width(listWidth) else Modifier.fillMaxSize()) { list() }
            }
            if (wide) VerticalDivider()
            if (detail != null) {
                Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
            } else if (wide) {
                Surface(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.pane_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
