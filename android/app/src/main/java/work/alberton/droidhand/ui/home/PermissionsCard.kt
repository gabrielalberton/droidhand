package work.alberton.droidhand.ui.home

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.R
import work.alberton.droidhand.ui.PermissionState
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.IconBadge
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.copy
import work.alberton.droidhand.ui.openAppInfo
import work.alberton.droidhand.ui.theme.LocalStatusColors

@SuppressLint("BatteryLife")
@Composable
internal fun PermissionsCard(p: PermissionState, refresh: () -> Unit) {
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        refresh()
    }
    Card {
        PermRow(R.drawable.ic_accessibility, R.string.perm_a11y, R.string.perm_a11y_desc, p.accessibility, required = true,
            action = R.string.action_enable, onAction = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
            Text(
                stringResource(R.string.perm_a11y_restricted),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { openAppInfo(ctx) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(R.string.action_app_info))
            }
        }
        RowDivider()
        PermRow(R.drawable.ic_bell, R.string.perm_notif, R.string.perm_notif_desc, p.notifications, required = true,
            action = R.string.action_allow, onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        RowDivider()
        PermRow(R.drawable.ic_battery, R.string.perm_battery, R.string.perm_battery_desc, p.battery, required = false,
            action = R.string.action_allow, onAction = {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            })
        RowDivider()
        PermRow(R.drawable.ic_key, R.string.perm_secure, R.string.perm_secure_desc, p.secureSettings, required = false,
            action = null, onAction = {}) {
            Text(
                stringResource(R.string.perm_secure_how),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            CommandBox(PermissionState.GRANT_COMMAND)
        }
    }
}


/** One permission: badge, text, and either a check or an action button; [extra] shows while not granted. */
@Composable
private fun PermRow(
    @DrawableRes icon: Int,
    title: Int,
    desc: Int,
    granted: Boolean,
    required: Boolean,
    action: Int?,
    onAction: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    val s = LocalStatusColors.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (granted) IconBadge(icon, s.ok, s.okContainer)
            else if (required) IconBadge(icon, s.warn, s.warnContainer)
            else IconBadge(icon, cs.onSurfaceVariant, cs.surfaceContainerHigh)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (!granted) {
                    Text(
                        stringResource(if (required) R.string.required else R.string.optional).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (required) s.warn else cs.onSurfaceVariant,
                    )
                }
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                if (!granted && extra != null) {
                    Spacer(Modifier.height(8.dp))
                    extra()
                }
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Icon(painterResource(R.drawable.ic_check_circle), null, tint = s.ok, modifier = Modifier.padding(top = 6.dp).size(22.dp))
            } else if (action != null) {
                FilledTonalButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(34.dp)) {
                    Text(stringResource(action), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}


@Composable
private fun CommandBox(cmd: String) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            cmd, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        IconButton(onClick = { copy(ctx, cmd) }) {
            Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.action_copy), Modifier.size(18.dp))
        }
    }
}
