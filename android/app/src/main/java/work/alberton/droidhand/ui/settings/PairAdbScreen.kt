package work.alberton.droidhand.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import work.alberton.droidhand.R
import work.alberton.droidhand.adb.AdbPairing
import work.alberton.droidhand.relay.RelayClient
import work.alberton.droidhand.ui.Live
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.Note
import work.alberton.droidhand.ui.components.Page
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.components.SectionLabel

/** Permissions → Secure settings → Pair from the app: adb pairing without the computer at hand. */
@Composable
fun PairAdbScreen(relay: RelayClient.State, live: Live, onBack: () -> Unit, onConnection: () -> Unit) {
    val ctx = LocalContext.current
    val state by AdbPairing.state.collectAsState()
    var devOn by remember { mutableStateOf(devOptions(ctx)) }
    LaunchedEffect(Unit) { while (true) { devOn = devOptions(ctx); delay(1500) } }
    val connected = relay == RelayClient.State.CONNECTED
    val ready = connected && live.perms.accessibility

    Page(stringResource(R.string.pair_title), onBack) {
        Text(
            stringResource(R.string.pair_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        // Say what is missing and offer the way there, instead of a silently grey button.
        if (!connected) {
            Note(stringResource(R.string.pair_need_relay))
            TextButton(onClick = onConnection) { Text(stringResource(R.string.pair_open_connection)) }
        }
        if (!live.perms.accessibility) {
            Note(stringResource(R.string.pair_need_a11y))
            TextButton(onClick = { open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text(stringResource(R.string.action_enable)) }
        }

        SectionLabel(stringResource(R.string.pair_steps))
        Card {
            Step(1, stringResource(if (devOn) R.string.pair_s1_done else R.string.pair_s1))
            if (!devOn) {
                TextButton(onClick = { open(ctx, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) },
                    modifier = Modifier.padding(start = 56.dp)) { Text(stringResource(R.string.pair_open_about)) }
            }
            RowDivider()
            Step(2, stringResource(R.string.pair_s2))
            RowDivider()
            Step(3, stringResource(R.string.pair_s3))
            Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 14.dp)) {
                Button(
                    enabled = state !is AdbPairing.State.Sending,
                    onClick = {
                        val why = when {
                            !connected -> R.string.pair_need_relay
                            !live.perms.accessibility -> R.string.pair_need_a11y
                            !devOn -> R.string.pair_s1
                            else -> null
                        }
                        if (why != null) { android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show(); return@Button }
                        AdbPairing.arm()
                        open(ctx, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless"))
                    },
                ) { Text(stringResource(R.string.pair_start)) }
            }
        }

        SectionLabel(stringResource(R.string.pair_status))
        Card {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state is AdbPairing.State.Sending) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 3.dp)
                Text(statusText(state), style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionLabel(stringResource(R.string.pair_manual))
        Card {
            var code by remember { mutableStateOf("") }
            var port by remember { mutableStateOf("") }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pair_manual_desc), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f),
                        label = { Text(stringResource(R.string.pair_code)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f),
                        label = { Text(stringResource(R.string.pair_port)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedButton(
                    enabled = ready && code.length == 6 && port.isNotEmpty() && state !is AdbPairing.State.Sending,
                    onClick = { AdbPairing.send(ctx, code, port.toInt()) },
                ) { Text(stringResource(R.string.pair_send)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Note(stringResource(R.string.pair_note))
    }
}

@Composable
private fun statusText(s: AdbPairing.State): String = when (s) {
    AdbPairing.State.Idle -> stringResource(R.string.pair_st_idle)
    AdbPairing.State.Waiting -> stringResource(R.string.pair_st_waiting)
    is AdbPairing.State.Sending -> stringResource(R.string.pair_st_sending)
    is AdbPairing.State.Done -> stringResource(if (s.granted) R.string.pair_st_done else R.string.pair_st_done_partial) +
        (s.note?.let { "\n$it" } ?: "")
    is AdbPairing.State.Failed -> stringResource(R.string.pair_st_failed, s.message)
}

private fun devOptions(ctx: android.content.Context) =
    Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

private fun open(ctx: android.content.Context, i: Intent) {
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
