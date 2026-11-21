package work.alberton.droidhand.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.R
import work.alberton.droidhand.bg.BackgroundSession
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.ui.Live
import work.alberton.droidhand.ui.bgSession
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.Page
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.components.SectionLabel
import work.alberton.droidhand.ui.components.SettingRow
import work.alberton.droidhand.ui.components.StatusDot
import work.alberton.droidhand.ui.theme.LocalStatusColors

@Composable
fun BackgroundScreen(prefs: Prefs, live: Live, onBack: () -> Unit) {
    var mode by remember { mutableStateOf(prefs.bgMode) }
    var opacity by remember { mutableStateOf(prefs.windowOpacity) }
    val s = LocalStatusColors.current
    val cs = MaterialTheme.colorScheme

    Page(stringResource(R.string.nav_bg), onBack) {
        Text(
            stringResource(R.string.bg_intro),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        SectionLabel(stringResource(R.string.bg_method))
        Card {
            listOf(
                Triple(Prefs.BG_AGENT, R.string.bg_agent, R.string.bg_agent_desc),
                Triple(Prefs.BG_NATIVE, R.string.bg_native, R.string.bg_native_desc),
                Triple(Prefs.BG_OWN, R.string.bg_own, R.string.bg_own_desc),
            ).forEachIndexed { i, (key, title, desc) ->
                if (i > 0) RowDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = mode == key, onClick = { mode = key; prefs.bgMode = key })
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                        RadioButton(selected = mode == key, onClick = null)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
        }

        SectionLabel(
            stringResource(R.string.bg_opacity),
            if (opacity >= 0.99f) stringResource(R.string.bg_opaque) else "${(opacity * 100).toInt()}%",
        )
        Card {
            Slider(
                value = opacity,
                onValueChange = {
                    prefs.windowOpacity = it
                    opacity = prefs.windowOpacity // snaps to 100% near the end
                    runCatching { bgSession()?.setOpacity(opacity) }
                },
                valueRange = 0.3f..1f,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        SectionLabel(stringResource(R.string.bg_session))
        Card {
            SettingRow(
                R.drawable.ic_developer, stringResource(R.string.bg_helper),
                stringResource(if (live.helper) R.string.bg_helper_on else R.string.bg_helper_off),
            ) {
                if (live.helper) StatusDot(s.ok, pulse = false)
            }
            RowDivider()
            SettingRow(
                R.drawable.ic_window, stringResource(R.string.bg_display),
                if (live.bgMode == null) stringResource(R.string.bg_session_none)
                else stringResource(
                    R.string.bg_session_active,
                    stringResource(if (live.bgMode == BackgroundSession.Mode.OWN) R.string.bg_own else R.string.bg_native),
                    live.bgDisplay,
                ),
            ) {
                if (live.bgMode != null) StatusDot(s.ok, pulse = true)
            }
            if (live.bgMode != null) {
                FilledTonalButton(
                    onClick = { runCatching { bgSession()?.close("closed from the app") } },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.bg_close_now)) }
            }
        }
    }
}
