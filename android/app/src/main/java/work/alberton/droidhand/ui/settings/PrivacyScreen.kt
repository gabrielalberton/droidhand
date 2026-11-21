package work.alberton.droidhand.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.R
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.Note
import work.alberton.droidhand.ui.components.Page
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.components.SettingRow

@Composable
fun PrivacyScreen(prefs: Prefs, onBack: () -> Unit) {
    var suggestions by remember { mutableStateOf(prefs.suggestionLogging) }
    var ask by remember { mutableStateOf(prefs.askMainScreen) }
    Page(stringResource(R.string.nav_privacy), onBack) {
        Card {
            SettingRow(R.drawable.ic_window, stringResource(R.string.priv_ask_main), stringResource(R.string.priv_ask_main_desc),
                onClick = { ask = !ask; prefs.askMainScreen = ask }) {
                Spacer(Modifier.width(12.dp))
                Switch(checked = ask, onCheckedChange = { ask = it; prefs.askMainScreen = it })
            }
            RowDivider()
            SettingRow(R.drawable.ic_lightbulb, stringResource(R.string.priv_suggestions), stringResource(R.string.priv_suggestions_desc),
                onClick = {
                    suggestions = !suggestions; prefs.suggestionLogging = suggestions; RelayClient.current?.sendDeviceInfo()
                }) {
                Spacer(Modifier.width(12.dp))
                Switch(checked = suggestions, onCheckedChange = {
                    suggestions = it; prefs.suggestionLogging = it; RelayClient.current?.sendDeviceInfo()
                })
            }
        }
        Spacer(Modifier.height(16.dp))
        Note(stringResource(R.string.priv_panic_note))
        Note(stringResource(R.string.priv_audit_note))
        Note(stringResource(R.string.priv_token_note))
        UnlockSection(prefs)
    }
}
