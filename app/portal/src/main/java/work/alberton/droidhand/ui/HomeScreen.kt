package work.alberton.droidhand.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.BuildConfig
import work.alberton.droidhand.R
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient.State
import work.alberton.droidhand.service.RelayService
import work.alberton.droidhand.ui.theme.LocalStatusColors

@Composable
fun HomeScreen(
    prefs: Prefs,
    ui: UiSettings,
    live: Live,
    relay: State,
    onOpen: (Route) -> Unit,
    refresh: () -> Unit,
) {
    Page(title = null, onBack = null) {
        Header()
        Spacer(Modifier.height(24.dp))
        StatusCard(prefs, live, relay, onOpen)

        val p = live.perms
        SectionLabel(
            stringResource(R.string.section_permissions),
            if (p.granted == p.total) stringResource(R.string.permissions_all_ok)
            else stringResource(R.string.permissions_count, p.granted, p.total),
        )
        PermissionsCard(p, refresh)

        SectionLabel(stringResource(R.string.section_settings))
        Card {
            SettingRow(R.drawable.ic_link, stringResource(R.string.nav_connection),
                prefs.relayUrl.ifBlank { stringResource(R.string.conn_not_configured) }.removePrefix("ws://").removePrefix("wss://"),
                onClick = { onOpen(Route.CONNECTION) }) { Chevron() }
            RowDivider()
            SettingRow(R.drawable.ic_window, stringResource(R.string.nav_bg), stringResource(bgModeLabel(prefs.bgMode)),
                onClick = { onOpen(Route.BACKGROUND) }) { Chevron() }
            RowDivider()
            SettingRow(R.drawable.ic_palette, stringResource(R.string.nav_appearance), appearanceSummary(ui),
                onClick = { onOpen(Route.APPEARANCE) }) { Chevron() }
            RowDivider()
            SettingRow(R.drawable.ic_shield, stringResource(R.string.nav_privacy),
                stringResource(if (prefs.askMainScreen) R.string.priv_ask_on else R.string.priv_ask_off),
                onClick = { onOpen(Route.PRIVACY) }) { Chevron() }
        }
        Text(
            stringResource(R.string.footer_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

fun bgModeLabel(mode: String) = when (mode) {
    Prefs.BG_NATIVE -> R.string.bg_native
    Prefs.BG_OWN -> R.string.bg_own
    else -> R.string.bg_agent
}

@Composable
private fun appearanceSummary(ui: UiSettings): String {
    val theme = stringResource(
        when (ui.theme) { Prefs.THEME_LIGHT -> R.string.theme_light; Prefs.THEME_DARK -> R.string.theme_dark; else -> R.string.theme_system },
    )
    val color = if (ui.dynamic) stringResource(R.string.app_dynamic) else stringResource(ui.accent.label)
    return "$theme · $color"
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1F2130)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(76.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(prefs: Prefs, live: Live, relay: State, onOpen: (Route) -> Unit) {
    val ctx = LocalContext.current
    val s = LocalStatusColors.current
    val cs = MaterialTheme.colorScheme
    val configured = prefs.relayUrl.isNotBlank()
    val state = if (!configured) State.NOT_CONFIGURED else relay
    val host = prefs.relayUrl.removePrefix("ws://").removePrefix("wss://")

    val dotColor by animateColorAsState(
        when (state) {
            State.CONNECTED -> s.ok
            State.CONNECTING -> s.warn
            State.UNAUTHORIZED -> cs.error
            else -> cs.outline
        },
        label = "dot",
    )
    val title = stringResource(
        when (state) {
            State.CONNECTED -> R.string.status_connected
            State.CONNECTING -> R.string.status_connecting
            State.UNAUTHORIZED -> R.string.status_unauthorized
            State.NOT_CONFIGURED -> R.string.status_not_configured
            State.OFF -> R.string.status_off
        },
    )
    val detail = when {
        state == State.NOT_CONFIGURED -> stringResource(R.string.status_not_configured_detail)
        !live.perms.accessibility -> stringResource(R.string.status_needs_a11y)
        state == State.CONNECTED -> stringResource(R.string.status_connected_detail, host)
        state == State.CONNECTING -> stringResource(R.string.status_connecting_detail, host)
        state == State.UNAUTHORIZED -> stringResource(R.string.status_unauthorized_detail)
        else -> stringResource(R.string.status_off_detail)
    }

    Card(Modifier.animateContentSize()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(dotColor, pulse = state == State.CONNECTING || state == State.CONNECTED)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(6.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            if (live.bgMode != null) {
                Spacer(Modifier.height(12.dp))
                Pill(stringResource(R.string.bg_active_chip), cs.onPrimaryContainer, cs.primaryContainer)
            }
            Spacer(Modifier.height(20.dp))
            val running = state == State.CONNECTED || state == State.CONNECTING || state == State.UNAUTHORIZED
            val shape = RoundedCornerShape(16.dp)
            val mod = Modifier.fillMaxWidth().height(52.dp)
            when {
                !configured -> Button(onClick = { onOpen(Route.CONNECTION) }, mod, shape = shape) {
                    Text(stringResource(R.string.action_setup))
                }
                running -> FilledTonalButton(
                    onClick = { prefs.serviceEnabled = false; RelayService.stop(ctx) }, mod, shape = shape,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = cs.errorContainer, contentColor = cs.onErrorContainer),
                ) {
                    Icon(painterResource(R.drawable.ic_power), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_stop))
                }
                else -> Button(onClick = { prefs.serviceEnabled = true; RelayService.start(ctx) }, mod, shape = shape) {
                    Icon(painterResource(R.drawable.ic_power), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_start))
                }
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun PermissionsCard(p: PermissionState, refresh: () -> Unit) {
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

fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("DroidHand", text))
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
}

fun openAppInfo(ctx: Context) =
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
