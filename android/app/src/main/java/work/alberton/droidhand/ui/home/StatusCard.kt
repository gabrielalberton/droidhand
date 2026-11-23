package work.alberton.droidhand.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.R
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient.State
import work.alberton.droidhand.service.RelayService
import work.alberton.droidhand.ui.Live
import work.alberton.droidhand.ui.Route
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.Pill
import work.alberton.droidhand.ui.components.StatusDot
import work.alberton.droidhand.ui.theme.LocalStatusColors

@Composable
internal fun StatusCard(prefs: Prefs, live: Live, relay: State, onOpen: (Route) -> Unit) {
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
    // Still connecting after a while: most often the phone cannot reach the computer at all.
    var slow by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state) {
        slow = false
        if (state == State.CONNECTING) { kotlinx.coroutines.delay(15_000); slow = true }
    }
    val detail = when {
        state == State.NOT_CONFIGURED -> stringResource(R.string.status_not_configured_detail)
        !live.perms.accessibility -> stringResource(R.string.status_needs_a11y)
        state == State.CONNECTED -> stringResource(R.string.status_connected_detail, host)
        state == State.CONNECTING && slow -> stringResource(R.string.status_connecting_detail, host) + "\n" + stringResource(R.string.status_connecting_slow)
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
