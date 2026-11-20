package work.alberton.droidhand.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import work.alberton.droidhand.R
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient
import work.alberton.droidhand.service.RelayService

/**
 * Minimal setup/status UI (P1 skeleton). The feature-suggestion switch is OFF by
 * default; everything else is manual, auditable configuration.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        if (prefs.serviceEnabled && prefs.relayUrl.isNotBlank()) RelayService.start(this)
        setContent { MaterialTheme { Screen(prefs, this) } }
    }
}

@Composable
private fun Screen(prefs: Prefs, ctx: Context) {
    var url by remember { mutableStateOf(prefs.relayUrl) }
    var token by remember { mutableStateOf(prefs.token) }
    var showToken by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(prefs.suggestionLogging) }
    var bgMode by remember { mutableStateOf(prefs.bgMode) }
    var opacity by remember { mutableStateOf(prefs.windowOpacity) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("DroidHand", style = MaterialTheme.typography.headlineSmall)
        Text("Agent control of this phone. Only enable if you set this up yourself.")

        OutlinedTextField(
            value = url,
            onValueChange = { url = it; prefs.relayUrl = it },
            label = { Text("Relay URL (ws(s)://<nuc-tailscale-ip>:8787)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; prefs.token = it },
            label = { Text("Token") },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        painter = painterResource(if (showToken) R.drawable.ic_eye_off else R.drawable.ic_eye),
                        contentDescription = if (showToken) "Hide token" else "Show token",
                    )
                }
            },
            // Always a password field for accessibility: even when shown on screen,
            // the agent's snapshot reads it as masked.
            modifier = Modifier.fillMaxWidth().semantics { password() },
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = suggestions, onCheckedChange = {
                suggestions = it
                prefs.suggestionLogging = it
                RelayClient.current?.sendDeviceInfo()
            })
            Text("Allow the agent to log feature suggestions (off by default)")
        }

        Text("Background window", style = MaterialTheme.typography.titleMedium)
        Text(
            "Where the agent works while you keep using the phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        listOf(
            Prefs.BG_AGENT to "Let the agent decide (both available)",
            Prefs.BG_NATIVE to "Native: works with debugging OFF; system-drawn window (drag its handle to the X to close; cannot be hidden)",
            Prefs.BG_OWN to "Own window: opaque/translucent, resizable, can be hidden while the agent works; needs debugging ON during the session",
        ).forEach { (key, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().selectable(selected = bgMode == key, onClick = { bgMode = key; prefs.bgMode = key }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = bgMode == key, onClick = null)
                Text(label, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Text("Own window opacity: ${(opacity * 100).toInt()}%" + if (opacity >= 0.99f) " (opaque)" else "")
        Slider(
            value = opacity,
            onValueChange = {
                prefs.windowOpacity = it
                opacity = prefs.windowOpacity // snaps to 100% near the end
                runCatching { work.alberton.droidhand.a11y.DroidHandAccessibilityService.instance?.bg?.setOpacity(it) }
            },
            valueRange = 0.3f..1f,
        )

        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open Accessibility settings")
        }
        Button(onClick = { prefs.serviceEnabled = true; RelayService.start(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("Start agent service")
        }
        Button(onClick = { prefs.serviceEnabled = false; RelayService.stop(ctx) }, modifier = Modifier.fillMaxWidth()) {
            Text("Disconnect (panic)")
        }

        Text(
            "Grant privileged settings once via ADB:\n" +
                "pm grant work.alberton.droidhand android.permission.WRITE_SECURE_SETTINGS",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
