package work.alberton.droidhand.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.BuildConfig
import work.alberton.droidhand.R
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient.State
import work.alberton.droidhand.ui.Live
import work.alberton.droidhand.ui.Route
import work.alberton.droidhand.ui.UiSettings
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.Chevron
import work.alberton.droidhand.ui.components.Page
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.components.SectionLabel
import work.alberton.droidhand.ui.components.SettingRow

@Composable
fun HomeScreen(
    prefs: Prefs,
    ui: UiSettings,
    live: Live,
    relay: State,
    updates: UpdateModel,
    onOpen: (Route) -> Unit,
    refresh: () -> Unit,
) {
    Page(title = null, onBack = null) {
        Header(updates)
        Spacer(Modifier.height(24.dp))
        StatusCard(prefs, live, relay, onOpen)
        UpdateCard(updates)

        SectionLabel(stringResource(R.string.section_quick))
        QuickControls(live, refresh, onPair = { onOpen(Route.PAIR_ADB) })

        val p = live.perms
        SectionLabel(
            stringResource(R.string.section_permissions),
            if (p.granted == p.total) stringResource(R.string.permissions_all_ok)
            else stringResource(R.string.permissions_count, p.granted, p.total),
        )
        PermissionsCard(p, refresh, onPair = { onOpen(Route.PAIR_ADB) })

        SectionLabel(stringResource(R.string.section_settings))
        Card {
            SettingRow(R.drawable.ic_link, stringResource(R.string.nav_connection),
                prefs.relayUrl.ifBlank { stringResource(R.string.conn_not_configured) }.removePrefix("ws://").removePrefix("wss://"),
                onClick = { onOpen(Route.CONNECTION) }) { Chevron() }
            RowDivider()
            SettingRow(R.drawable.ic_developer, stringResource(R.string.nav_relay), stringResource(R.string.relay_row_summary),
                onClick = { onOpen(Route.RELAY) }) { Chevron() }
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
private fun Header(updates: UpdateModel) {
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
        Spacer(Modifier.weight(1f))
        UpdateButton(updates)
    }
}
