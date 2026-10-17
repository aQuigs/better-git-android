package com.sqftware.safegit.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.about.Licenses

/**
 * The app's [version], what it ships with each license, and the source offer git's license calls for; [licenses] is null
 * while they load. [onOpen] opens a web or mailto address.
 */
@Composable
fun AboutScreen(version: String, licenses: Licenses?, onOpen: (uri: String) -> Unit) {
    // The name of the package whose license is open, or APP_LIBRARIES
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val name = open
    if (licenses != null && name != null) {
        BackHandler { open = null }
        val text = if (name == APP_LIBRARIES) licenses.apache else licenses.packages.firstOrNull { it.name == name }?.license
        LicenseText(if (name == APP_LIBRARIES) stringResource(R.string.about_app_libraries) else name, text.orEmpty())
        return
    }

    val subject = stringResource(R.string.about_offer_subject, version)
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp)) {
            item {
                Column {
                    Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.about_app), Modifier.padding(top = 16.dp))
                    TextButton(onClick = { onOpen(SOURCE) }) { Text(stringResource(R.string.about_source)) }
                }
            }

            item { Heading(stringResource(R.string.about_git_title), stringResource(R.string.about_git_body)) }
            items(licenses?.packages.orEmpty(), key = { it.name }) { bundled ->
                ListItem(
                    headlineContent = { Text(bundled.name) },
                    supportingContent = { Text(bundled.version) },
                    modifier = Modifier.clickable { open = bundled.name },
                )
            }

            item {
                Column {
                    Heading(stringResource(R.string.about_offer_title), stringResource(R.string.about_offer, OFFER_EMAIL))
                    OutlinedButton(onClick = { onOpen("mailto:$OFFER_EMAIL?subject=${Uri.encode(subject)}") }, Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.about_offer_ask))
                    }
                    TextButton(onClick = { onOpen(TERMUX_RECIPES) }) { Text(stringResource(R.string.about_offer_termux)) }
                }
            }

            item { Heading(stringResource(R.string.about_libraries_title)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_app_libraries)) },
                    supportingContent = { Text(stringResource(R.string.about_apache)) },
                    modifier = Modifier.clickable(enabled = licenses != null) { open = APP_LIBRARIES },
                )
            }
        }
    }
}

@Composable
private fun Heading(title: String, body: String? = null) {
    Column(Modifier.padding(top = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body != null) Text(body, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LicenseText(title: String, text: String) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            // License texts are laid out in fixed-width lines that wrapping would break up
            Text(
                text,
                Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
    }
}

private const val APP_LIBRARIES = ""
private const val SOURCE = "https://github.com/aQuigs/better-git-android"
private const val TERMUX_RECIPES = "https://github.com/termux/termux-packages"
private const val OFFER_EMAIL = "sqftware@gmail.com"
