package work.alberton.droidhand.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import work.alberton.droidhand.R
import work.alberton.droidhand.compat.InstallerCompat
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.components.SettingRow
import work.alberton.droidhand.ui.theme.LocalStatusColors

/** Current state of the "compatibility with banking apps" option, refreshed while visible. */
@Composable
internal fun rememberInstallerStatus(prefs: Prefs): InstallerCompat.Status {
    val ctx = LocalContext.current
    val working by InstallerCompat.working.collectAsState()
    val error by InstallerCompat.lastError.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    return InstallerCompat.status(ctx, prefs, working, error)
}

@Composable
internal fun installerStatusText(s: InstallerCompat.Status): String = when (s) {
    InstallerCompat.Status.Off -> stringResource(R.string.bank_status_off)
    InstallerCompat.Status.Active -> stringResource(R.string.bank_status_active)
    InstallerCompat.Status.Pending -> stringResource(R.string.bank_status_pending)
    InstallerCompat.Status.Working -> stringResource(R.string.bank_status_working)
    is InstallerCompat.Status.Failed -> stringResource(R.string.bank_status_failed, s.message)
}

/** Privacy & safety: the option, its status and the warning. On by default. */
@Composable
fun BankCompatSection(prefs: Prefs) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(prefs.playInstaller) }
    val status = rememberInstallerStatus(prefs)
    val s = LocalStatusColors.current
    Card {
        SettingRow(R.drawable.ic_shield, stringResource(R.string.bank_title), installerStatusText(status),
            onClick = { on = !on; prefs.playInstaller = on; if (on) RelayClient.current?.let { InstallerCompat.maybeFix(ctx, it, force = true) } }) {
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = {
                on = it; prefs.playInstaller = it
                if (it) RelayClient.current?.let { r -> InstallerCompat.maybeFix(ctx, r, force = true) }
            })
        }
        RowDivider()
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(R.drawable.ic_error), null, tint = s.warn, modifier = Modifier.size(22.dp))
            Column {
                Text(stringResource(R.string.bank_warning_title), style = MaterialTheme.typography.titleSmall, color = s.warn)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.bank_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (on && status !is InstallerCompat.Status.Active && status !is InstallerCompat.Status.Working && RelayClient.current != null) {
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = { RelayClient.current?.let { InstallerCompat.maybeFix(ctx, it, force = true) } }) {
                        Text(stringResource(R.string.bank_apply_now))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
}
