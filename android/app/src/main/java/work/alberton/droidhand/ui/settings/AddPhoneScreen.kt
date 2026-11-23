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

private const val APP = "work.alberton.droidhand"

/**
 * Step-by-step guide to connect one more phone to the same relay (opened from the Relay
 * screen). Nothing here changes settings: phones are configured on the computer. The
 * relay address this phone uses is filled in, since the new phone uses the same one.
 */
@Composable
fun AddPhoneScreen(prefs: Prefs, onBack: () -> Unit) {
    val relay = prefs.relayUrl.ifBlank { "ws://<computer-tailnet-ip>:8787" }
    val token = "~/.config/droidhand/devices/<id>.token"

    Page(stringResource(R.string.add_phone_title), onBack) {
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

        SectionLabel(stringResource(R.string.add_phone_on_phone))
        Card {
            Step(4, stringResource(R.string.add_phone_s4))
            RowDivider()
            Step(5, stringResource(R.string.add_phone_s5), "https://github.com/gabrielalberton/droidhand/releases/latest")
            RowDivider()
            Step(6, stringResource(R.string.add_phone_s6, relay), "cat $token")
            RowDivider()
            Step(7, stringResource(R.string.add_phone_s7))
            RowDivider()
            Step(8, stringResource(R.string.add_phone_s8),
                "adb shell pm grant $APP android.permission.WRITE_SECURE_SETTINGS",
                stringResource(R.string.add_phone_s8_note))
        }

        SectionLabel(stringResource(R.string.add_phone_shortcut))
        Card {
            Step(null, stringResource(R.string.add_phone_shortcut_text),
                "RELAY_URL=$relay DROIDHAND_TOKEN_FILE=$token deploy/install-phone.sh <serial>")
        }

        SectionLabel(stringResource(R.string.add_phone_check))
        Card { Step(null, stringResource(R.string.add_phone_check_text)) }
        Spacer(Modifier.padding(top = 12.dp))
        Note(stringResource(R.string.add_phone_footer))
    }
}

/** One numbered step: text, an optional command (tap to copy) and an optional note. */
@Composable
private fun Step(n: Int?, text: String, code: String? = null, note: String? = null) {
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
