package work.alberton.droidhand.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.json.JSONObject
import work.alberton.droidhand.R
import work.alberton.droidhand.relay.RelayClient
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.Chevron
import work.alberton.droidhand.ui.components.SettingRow
import work.alberton.droidhand.ui.components.Note
import work.alberton.droidhand.ui.components.Page
import work.alberton.droidhand.ui.components.Pill
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.components.SectionLabel
import work.alberton.droidhand.ui.components.StatusDot
import work.alberton.droidhand.ui.theme.LocalStatusColors
import java.time.Instant

/** What the relay status request gave. */
private sealed interface Load {
    data object Loading : Load
    data object Offline : Load
    data object Unsupported : Load
    data object NotAdmin : Load
    data class Failed(val error: String) : Load
    data class Ok(val status: JSONObject) : Load
}

private suspend fun fetchStatus(): Load {
    val client = RelayClient.current ?: return Load.Offline
    val res = client.request("relay_status") ?: return Load.Unsupported
    if (res.optBoolean("ok")) return res.optJSONObject("data")?.let { Load.Ok(it) } ?: Load.Failed("empty answer")
    val error = res.optString("error")
    return when {
        error.startsWith("not_admin") -> Load.NotAdmin
        error.startsWith("unsupported request") -> Load.Unsupported
        else -> Load.Failed(error)
    }
}

private fun ago(iso: String?): String? = iso?.takeIf { it.isNotBlank() && it != "null" }?.let {
    runCatching {
        DateUtils.getRelativeTimeSpanString(Instant.parse(it).toEpochMilli(), System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
            .toString().replaceFirstChar { c -> c.lowercase() } // "há 5 minutos", to follow a label
    }.getOrNull()
}

/**
 * Read-only view of the relay this phone is connected to: the relay version, how many
 * agent sessions are attached, and every phone it knows with its connection state.
 * Only phones marked "admin" on the relay may see it. Refreshes while open.
 */
@Composable
fun RelayScreen(relay: RelayClient.State, onBack: () -> Unit, onAddPhone: () -> Unit) {
    var load by remember { mutableStateOf<Load>(Load.Loading) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, relay) {
        if (relay != RelayClient.State.CONNECTED) { load = Load.Offline; return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { load = fetchStatus(); delay(3000) }
        }
    }

    Page(stringResource(R.string.nav_relay), onBack) {
        when (val l = load) {
            Load.Loading -> Note(stringResource(R.string.relay_loading))
            Load.Offline -> Note(stringResource(R.string.relay_offline))
            Load.Unsupported -> Note(stringResource(R.string.relay_unsupported))
            Load.NotAdmin -> Note(stringResource(R.string.relay_not_admin))
            is Load.Failed -> Note(stringResource(R.string.relay_failed, l.error))
            is Load.Ok -> RelayStatusView(l.status)
        }
        // How to connect one more phone: useful whatever the status above says.
        Spacer(Modifier.padding(top = 4.dp))
        Card {
            SettingRow(R.drawable.ic_developer, stringResource(R.string.add_phone_row), stringResource(R.string.add_phone_row_sub),
                onClick = onAddPhone) { Chevron() }
        }
    }
}

@Composable
private fun RelayStatusView(st: JSONObject) {
    val devices = st.optJSONArray("devices")
    val count = devices?.length() ?: 0
    val online = (0 until count).count { devices!!.optJSONObject(it)?.optBoolean("connected") == true }
    val self = st.optString("self")

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.relay_version, st.optString("version")), style = MaterialTheme.typography.bodyLarge)
            ago(st.optString("startedAt"))?.let {
                Text(stringResource(R.string.relay_started, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.relay_agents, st.optInt("agents")), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    SectionLabel(stringResource(R.string.relay_phones), stringResource(R.string.relay_phones_online, online, count))
    Card {
        for (i in 0 until count) {
            if (i > 0) RowDivider()
            devices!!.optJSONObject(i)?.let { PhoneRow(it, it.optString("id") == self) }
        }
    }
    Spacer(Modifier.padding(top = 12.dp))
    Note(stringResource(R.string.relay_read_only))
}

@Composable
private fun PhoneRow(d: JSONObject, isSelf: Boolean) {
    val s = LocalStatusColors.current
    val cs = MaterialTheme.colorScheme
    val connected = d.optBoolean("connected")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.padding(top = 6.dp)) { StatusDot(if (connected) s.ok else cs.outline, pulse = false) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.optString("name"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                if (isSelf) Pill(stringResource(R.string.relay_this_phone), cs.onPrimaryContainer, cs.primaryContainer)
                if (d.optString("role") == "admin") Pill(stringResource(R.string.relay_admin), cs.onSecondaryContainer, cs.secondaryContainer)
            }
            val model = listOfNotNull(
                d.optString("model").ifBlank { null },
                d.optInt("androidSdk").takeIf { it > 0 }?.let { "SDK $it" },
                d.optString("appVersion").ifBlank { null }?.let { "app $it" },
            ).joinToString(" · ")
            val sub = MaterialTheme.typography.bodyMedium
            Text("${d.optString("id")}${if (model.isNotEmpty()) " · $model" else ""}", style = sub, color = cs.onSurfaceVariant)
            val since = if (connected) ago(d.optString("connectedSince"))?.let { stringResource(R.string.relay_connected_since, it) }
                else ago(d.optString("lastSeen"))?.let { stringResource(R.string.relay_last_seen, it) } ?: stringResource(R.string.relay_never_seen)
            since?.let { Text(it, style = sub, color = cs.onSurfaceVariant) }
            val adb = d.optJSONObject("adb")
            if (adb != null) {
                val mode = stringResource(if (adb.optString("mode") == "always") R.string.relay_adb_always else R.string.relay_adb_on_demand)
                val open = if (adb.optBoolean("sessionOpen")) stringResource(R.string.relay_adb_open) else ""
                Text("ADB: $mode$open", style = sub, color = cs.onSurfaceVariant)
            }
        }
    }
}
