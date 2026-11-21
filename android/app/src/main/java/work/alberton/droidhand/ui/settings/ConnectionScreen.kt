package work.alberton.droidhand.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.R
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient
import work.alberton.droidhand.service.RelayService
import work.alberton.droidhand.ui.components.Note
import work.alberton.droidhand.ui.components.Page

@Composable
fun ConnectionScreen(prefs: Prefs, relay: RelayClient.State, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(prefs.relayUrl) }
    var token by remember { mutableStateOf(prefs.token) }
    var showToken by remember { mutableStateOf(false) }
    val start = remember { prefs.relayUrl to prefs.token }

    // Reconnect once, when leaving, if anything changed.
    DisposableEffect(Unit) {
        onDispose {
            if ((prefs.relayUrl to prefs.token) != start && prefs.serviceEnabled) RelayService.restart(ctx)
        }
    }

    val urlError = url.isNotBlank() && !(url.startsWith("ws://") || url.startsWith("wss://"))
    val fieldShape = RoundedCornerShape(16.dp)
    val colors = OutlinedTextFieldDefaults.colors(
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    )

    Page(stringResource(R.string.nav_connection), onBack) {
        Text(
            stringResource(R.string.conn_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim(); prefs.relayUrl = url },
            label = { Text(stringResource(R.string.conn_url)) },
            placeholder = { Text(stringResource(R.string.conn_url_hint)) },
            isError = urlError,
            supportingText = if (urlError) { { Text(stringResource(R.string.conn_url_error)) } } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = fieldShape, colors = colors,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim(); prefs.token = token },
            label = { Text(stringResource(R.string.conn_token)) },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        painterResource(if (showToken) R.drawable.ic_eye_off else R.drawable.ic_eye),
                        stringResource(if (showToken) R.string.conn_hide_token else R.string.conn_show_token),
                    )
                }
            },
            shape = fieldShape, colors = colors,
            // Always a password field for accessibility: even when shown on screen,
            // the agent's snapshot reads it as masked.
            modifier = Modifier.fillMaxWidth().semantics { password() },
        )
        Spacer(Modifier.height(12.dp))
        Note(stringResource(R.string.conn_saved_note))
    }
}
