package com.sqftware.safegit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sqftware.safegit.R
import com.sqftware.safegit.auth.DeviceCode
import com.sqftware.safegit.auth.SignIn

/** The GitHub account syncs run as, and signing in by entering a code on github.com. */
@Composable
fun AccountCard(
    state: SignIn.State,
    onSignIn: () -> Unit,
    onOpen: (DeviceCode) -> Unit,
    onCancel: () -> Unit,
    onSignOut: () -> Unit,
) {
    if (state == SignIn.State.Loading) return
    OutlinedCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                SignIn.State.Loading -> Unit
                SignIn.State.Unavailable -> Text(stringResource(R.string.account_unavailable))
                SignIn.State.SignedOut -> {
                    Text(stringResource(R.string.account_signed_out))
                    Button(onClick = onSignIn) { Text(stringResource(R.string.account_sign_in)) }
                }
                SignIn.State.Starting -> Text(stringResource(R.string.account_starting))
                is SignIn.State.Waiting -> {
                    Text(stringResource(R.string.account_enter_code))
                    Text(state.code.userCode, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                    Text(state.code.verificationUri, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onOpen(state.code) }) { Text(stringResource(R.string.account_open)) }
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    }
                }
                is SignIn.State.SignedIn -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.account_signed_in, "@${state.account.login}"), Modifier.weight(1f))
                    TextButton(onClick = onSignOut) { Text(stringResource(R.string.account_sign_out)) }
                }
                is SignIn.State.Failed -> {
                    Text(stringResource(R.string.account_failed, state.reason), color = MaterialTheme.colorScheme.error)
                    Button(onClick = onSignIn) { Text(stringResource(R.string.account_retry)) }
                }
            }
        }
    }
}
