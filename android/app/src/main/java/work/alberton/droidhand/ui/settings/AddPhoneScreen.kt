package work.alberton.droidhand.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.R
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.Note
import work.alberton.droidhand.ui.components.Page
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.components.SectionLabel
import work.alberton.droidhand.ui.copy
import work.alberton.droidhand.ui.Invite
import work.alberton.droidhand.relay.RelayClient
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject

private const val APP = "work.alberton.droidhand"

/**
 * Step-by-step guide to connect one more phone to the same relay (opened from the Relay
 * screen). Nothing here changes settings: phones are configured on the computer. The
 * relay address this phone uses is filled in, since the new phone uses the same one.
 */
@Composable
fun AddPhoneScreen(prefs: Prefs, state: RelayClient.State, onBack: () -> Unit) {
    val relay = prefs.relayUrl.ifBlank { "ws://<computer-tailnet-ip>:8787" }
    val token = "~/.config/droidhand/devices/<id>.token"

    Page(stringResource(R.string.add_phone_title), onBack) {
        SectionLabel(stringResource(R.string.add_phone_in_app))
        CreateInApp(relay, state == RelayClient.State.CONNECTED)

        SectionLabel(stringResource(R.string.add_phone_on_phone))
        Card {
            Step(1, stringResource(R.string.add_phone_p1))
            RowDivider()
            Step(2, stringResource(R.string.add_phone_p2), Invite.DOWNLOAD_URL)
            RowDivider()
            Step(3, stringResource(R.string.add_phone_p3))
            RowDivider()
            Step(4, stringResource(R.string.add_phone_s7))
            RowDivider()
            Step(5, stringResource(R.string.add_phone_s8),
                "adb shell pm grant $APP android.permission.WRITE_SECURE_SETTINGS",
                stringResource(R.string.add_phone_s8_note))
        }

        SectionLabel(stringResource(R.string.add_phone_check))
        Card { Step(null, stringResource(R.string.add_phone_check_text)) }

        Spacer(Modifier.padding(top = 20.dp))
        Note(stringResource(R.string.add_phone_intro))
        SectionLabel(stringResource(R.string.add_phone_on_computer))
        Card {
            Step(1, stringResource(R.string.add_phone_s1),
                "mkdir -p ~/.config/droidhand/devices && openssl rand -hex 32 > $token && chmod 600 $token")
            RowDivider()
            Step(2, stringResource(R.string.add_phone_s2),
                "{ \"id\": \"<id>\", \"name\": \"<name>\", \"tokenFile\": \"devices/<id>.token\", \"role\": \"device\" }",
                stringResource(R.string.add_phone_s2_note))
            RowDivider()
            Step(3, stringResource(R.string.add_phone_s3), "systemctl --user restart droidhand-relay")
        }

        Card {
            Step(4, stringResource(R.string.add_phone_s6, relay), "cat $token")
        }

        SectionLabel(stringResource(R.string.add_phone_shortcut))
        Card {
            Step(null, stringResource(R.string.add_phone_shortcut_text),
                "RELAY_URL=$relay DROIDHAND_TOKEN_FILE=$token deploy/install-phone.sh <serial>")
        }

        Spacer(Modifier.padding(top = 12.dp))
        Note(stringResource(R.string.add_phone_footer))
    }
}

/** Admin phones: the relay creates the phone (token + devices.json) and the app makes an invite. */
@Composable
private fun CreateInApp(relay: String, connected: Boolean) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var idEdited by remember { mutableStateOf(false) }
    var admin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var invite by rememberSaveable { mutableStateOf<String?>(null) }
    var qrLink by rememberSaveable { mutableStateOf<String?>(null) }
    val idOk = Regex("^[a-z0-9][a-z0-9_-]{0,31}$").matches(id)

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val done = invite
            if (done != null) {
                Text(stringResource(R.string.add_phone_created), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                Text(stringResource(R.string.add_phone_invite_note), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                qrLink?.let { link ->
                    val bmp = remember(link) { Invite.qr(link, 720).asImageBitmap() }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Image(bmp, stringResource(R.string.add_phone_qr_cd),
                            modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.White))
                    }
                    Text(stringResource(R.string.add_phone_qr_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Text(done, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurface,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHighest).padding(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copy(ctx, done) }) { Text(stringResource(R.string.add_phone_copy_invite)) }
                    OutlinedButton(onClick = { Invite.share(ctx, done) }) { Text(stringResource(R.string.invite_share)) }
                }
                TextButton(onClick = { invite = null; qrLink = null; name = ""; id = ""; idEdited = false; admin = false }) {
                    Text(stringResource(R.string.add_phone_another))
                }
                return@Column
            }
            Text(stringResource(R.string.add_phone_in_app_text), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            OutlinedTextField(
                value = name, singleLine = true, enabled = !busy,
                onValueChange = { name = it.take(60); if (!idEdited) id = slug(it) },
                label = { Text(stringResource(R.string.add_phone_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = id, singleLine = true, enabled = !busy,
                onValueChange = { id = it.lowercase().take(32); idEdited = true },
                label = { Text(stringResource(R.string.add_phone_id)) },
                supportingText = { Text(stringResource(R.string.add_phone_id_hint)) },
                isError = id.isNotEmpty() && !idOk,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.add_phone_admin), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.add_phone_admin_sub), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                Switch(checked = admin, onCheckedChange = { admin = it }, enabled = !busy)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error) }
            if (!connected) Text(stringResource(R.string.add_phone_offline), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Button(
                enabled = connected && idOk && !busy,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val res = RelayClient.current?.request("add_device",
                            JSONObject().put("id", id).put("name", name.ifBlank { id }).put("role", if (admin) "admin" else "device"), 10_000)
                        busy = false
                        when {
                            res == null -> error = ctx.getString(R.string.add_phone_err_offline)
                            res.optBoolean("ok") -> res.optJSONObject("data")?.let { d ->
                                invite = Invite.text(ctx, d.optString("name"), relay, d.optString("token"))
                                qrLink = Invite.link(d.optString("name"), relay, d.optString("token"))
                            }
                            else -> error = res.optString("error").let { e ->
                                when {
                                    e.startsWith("not_admin") -> ctx.getString(R.string.relay_not_admin)
                                    e.startsWith("unsupported request") -> ctx.getString(R.string.add_phone_err_old_relay)
                                    e.startsWith("id_taken") -> ctx.getString(R.string.add_phone_err_taken, id)
                                    else -> e
                                }
                            }
                        }
                    }
                },
            ) { Text(stringResource(if (busy) R.string.add_phone_creating else R.string.add_phone_create)) }
        }
    }
}

/** "Tablet da Ana" -> "tablet-da-ana": a valid phone id suggested from the name. */
private fun slug(name: String): String =
    java.text.Normalizer.normalize(name.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)

/** One numbered step: text, an optional command (tap to copy) and an optional note. */
@Composable
internal fun Step(n: Int?, text: String, code: String? = null, note: String? = null) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        if (n != null) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Text("$n", style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            if (code != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainerHighest)
                        .clickable { copy(ctx, code) }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        code, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = cs.onSurface, softWrap = false,
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.add_phone_copy),
                        tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
