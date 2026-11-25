package work.alberton.droidhand.ui.home

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import work.alberton.droidhand.R
import work.alberton.droidhand.a11y.A11yToggle
import work.alberton.droidhand.adb.AdbSession
import work.alberton.droidhand.ui.Live
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.Chevron
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.components.SettingRow
import java.util.Date

/**
 * Two switches on the home screen: the accessibility service and USB debugging. With the
 * secure-settings grant they flip right here; without it the switch opens Android's own screen
 * (accessibility) or the pairing screen (ADB). Debugging started here closes by itself after 10 minutes.
 */
@Composable
internal fun QuickControls(live: Live, refresh: () -> Unit, onPair: () -> Unit, onOpenPrivacy: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = androidx.compose.runtime.remember { work.alberton.droidhand.data.Prefs(ctx) }
    val bank = work.alberton.droidhand.ui.settings.rememberInstallerStatus(prefs)
    Card {
        SettingRow(
            R.drawable.ic_accessibility, stringResource(R.string.quick_a11y),
            stringResource(if (live.perms.accessibility) R.string.quick_a11y_on else R.string.quick_a11y_off),
        ) {
            Switch(checked = live.perms.accessibility, onCheckedChange = { on ->
                if (!A11yToggle.set(ctx, on)) {
                    Toast.makeText(ctx, R.string.quick_a11y_manual, Toast.LENGTH_LONG).show()
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                refresh()
            })
        }
        RowDivider()
        val until = live.adbDeadline.takeIf { live.adbOn && it > System.currentTimeMillis() }
            ?.let { DateFormat.getTimeFormat(ctx).format(Date(it)) }
        SettingRow(
            R.drawable.ic_developer, stringResource(R.string.quick_adb),
            when {
                until != null -> stringResource(R.string.quick_adb_until, until)
                live.adbOn -> stringResource(R.string.quick_adb_on)
                else -> stringResource(R.string.quick_adb_off)
            },
        ) {
            Switch(checked = live.adbOn, onCheckedChange = { on ->
                if (!AdbSession.canWrite(ctx)) onPair()
                else {
                    if (on) AdbSession.enable(ctx, AdbSession.DEFAULT_TTL_S, wifi = false) else AdbSession.disable(ctx, "home switch")
                    refresh()
                }
            })
        }
        RowDivider()
        SettingRow(R.drawable.ic_shield, stringResource(R.string.bank_title), work.alberton.droidhand.ui.settings.installerStatusText(bank),
            onClick = onOpenPrivacy) { Chevron() }
    }
}
