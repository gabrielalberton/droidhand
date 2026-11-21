package work.alberton.droidhand.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.R
import work.alberton.droidhand.bg.BackgroundSession
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient
import work.alberton.droidhand.service.RelayService
import work.alberton.droidhand.ui.theme.Accent
import work.alberton.droidhand.ui.theme.LocalStatusColors

// ---------------------------------------------------------------- Connection

@Composable
fun ConnectionScreen(prefs: Prefs, relay: RelayClient.State, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(prefs.relayUrl) }
    var token by remember { mutableStateOf(prefs.token) }
    var showToken by remember { mutableStateOf(false) }
    val start = remember { prefs.relayUrl to prefs.token }

    // Reconnect once, when leaving, if anything changed.
    DisposableEffect(Unit) {
        onDispose {
            if ((prefs.relayUrl to prefs.token) != start && prefs.serviceEnabled) RelayService.restart(ctx)
        }
    }

    val urlError = url.isNotBlank() && !(url.startsWith("ws://") || url.startsWith("wss://"))
    val fieldShape = RoundedCornerShape(16.dp)
    val colors = OutlinedTextFieldDefaults.colors(
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    )

    Page(stringResource(R.string.nav_connection), onBack) {
        Text(
            stringResource(R.string.conn_intro),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim(); prefs.relayUrl = url },
            label = { Text(stringResource(R.string.conn_url)) },
            placeholder = { Text(stringResource(R.string.conn_url_hint)) },
            isError = urlError,
            supportingText = if (urlError) { { Text(stringResource(R.string.conn_url_error)) } } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = fieldShape, colors = colors,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim(); prefs.token = token },
            label = { Text(stringResource(R.string.conn_token)) },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        painterResource(if (showToken) R.drawable.ic_eye_off else R.drawable.ic_eye),
                        stringResource(if (showToken) R.string.conn_hide_token else R.string.conn_show_token),
                    )
                }
            },
            shape = fieldShape, colors = colors,
            // Always a password field for accessibility: even when shown on screen,
            // the agent's snapshot reads it as masked.
            modifier = Modifier.fillMaxWidth().semantics { password() },
        )
        Spacer(Modifier.height(12.dp))
        Note(stringResource(R.string.conn_saved_note))
    }
}

// ---------------------------------------------------------------- Background window

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

// ---------------------------------------------------------------- Appearance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(ui: UiSettings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var lang by remember { mutableStateOf(AppConfig.language(ctx)) }

    Page(stringResource(R.string.nav_appearance), onBack) {
        SectionLabel(stringResource(R.string.app_theme))
        Card {
            Segmented(
                listOf(Prefs.THEME_SYSTEM to R.string.theme_system, Prefs.THEME_LIGHT to R.string.theme_light, Prefs.THEME_DARK to R.string.theme_dark)
                    .map { it.first to stringResource(it.second) },
                ui.theme,
            ) {
                ui.theme = it
                ui.prefs.themeMode = it
                AppConfig.applyNightMode(ctx, it)
            }
        }

        SectionLabel(stringResource(R.string.app_accent))
        Card {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingRow(R.drawable.ic_palette, stringResource(R.string.app_dynamic), stringResource(R.string.app_dynamic_desc),
                    onClick = { ui.dynamic = !ui.dynamic; ui.prefs.dynamicColor = ui.dynamic }) {
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = ui.dynamic, onCheckedChange = { ui.dynamic = it; ui.prefs.dynamicColor = it })
                }
                RowDivider()
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp).alpha(if (ui.dynamic) 0.38f else 1f),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val dark = cs.background.luminanceIsDark()
                Accent.entries.forEach { a ->
                    val selected = !ui.dynamic && ui.accent == a
                    val name = stringResource(a.label)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .border(2.dp, if (selected) cs.onSurface else Color.Transparent, CircleShape)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(if (dark) a.dark else a.light)
                                .clickable(enabled = !ui.dynamic) { ui.accent = a; ui.prefs.accent = a.key }
                                .semantics { contentDescription = name },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(painterResource(R.drawable.ic_check), null, tint = if (dark) Color.Black else Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        SectionLabel(stringResource(R.string.app_language))
        Card {
            Segmented(
                listOf("" to stringResource(R.string.lang_system), "en" to "English", "pt-BR" to "Português"),
                when { lang.startsWith("pt") -> "pt-BR"; lang.startsWith("en") -> "en"; else -> "" },
            ) {
                lang = it
                AppConfig.setLanguage(ctx, it)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) (ctx as? Activity)?.recreate()
            }
        }
    }
}

private fun Color.luminanceIsDark() = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
        options.forEachIndexed { i, (key, label) ->
            SegmentedButton(
                selected = key == selected,
                onClick = { onSelect(key) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    inactiveContainerColor = Color.Transparent,
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    activeBorderColor = MaterialTheme.colorScheme.outline,
                ),
                icon = {},
            ) { Text(label, maxLines = 1) }
        }
    }
}

// ---------------------------------------------------------------- Privacy

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

// ---------------------------------------------------------------- Unlock with PIN (dangerous, opt-in)

/** Asks for the user's fingerprint/face or device PIN; the agent cannot pass this. */
fun confirmUser(activity: Activity, title: String, onOk: () -> Unit) {
    val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
        .setTitle(title)
        .setAllowedAuthenticators(
            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        .build()
    prompt.authenticate(
        android.os.CancellationSignal(), activity.mainExecutor,
        object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) = onOk()
        },
    )
}

@Composable
fun UnlockSection(prefs: Prefs) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val store = remember { work.alberton.droidhand.data.SecretStore(ctx) }
    val cs = MaterialTheme.colorScheme
    var enabled by remember { mutableStateOf(prefs.pinUnlockEnabled) }
    var hasPin by remember { mutableStateOf(store.hasPin) }
    var pin by remember { mutableStateOf("") }          // what is in the field
    var revealed by remember { mutableStateOf(false) }  // saved PIN loaded into the field
    var show by remember { mutableStateOf(false) }
    val authTitle = stringResource(R.string.unlock_auth_title)

    // While a PIN is visible, block screenshots and screen capture (including the agent's).
    DisposableEffect(show) {
        val w = activity?.window
        if (show) w?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }

    SectionLabel(stringResource(R.string.unlock_section))
    Card(color = cs.errorContainer.copy(alpha = 0.55f)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(painterResource(R.drawable.ic_error), null, tint = cs.error, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.unlock_danger_title), style = MaterialTheme.typography.titleMedium, color = cs.onErrorContainer)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.unlock_danger_body), style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Card {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter { it.isLetterOrDigit() }.take(16) },
                label = { Text(stringResource(R.string.unlock_pin)) },
                placeholder = { if (hasPin && !revealed) Text(stringResource(R.string.unlock_pin_saved)) },
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                trailingIcon = {
                    IconButton(onClick = {
                        if (show) { show = false; return@IconButton }
                        val a = activity ?: return@IconButton
                        confirmUser(a, authTitle) {
                            if (hasPin && !revealed && pin.isEmpty()) { pin = store.pin.orEmpty(); revealed = true }
                            show = true
                        }
                    }) {
                        Icon(painterResource(if (show) R.drawable.ic_eye_off else R.drawable.ic_eye),
                            stringResource(if (show) R.string.conn_hide_token else R.string.conn_show_token))
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().semantics { password() },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    enabled = pin.length >= 4 && (!revealed || pin != store.pin),
                    onClick = {
                        val a = activity ?: return@FilledTonalButton
                        confirmUser(a, authTitle) {
                            store.pin = pin; hasPin = true; revealed = true; show = false
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                ) { Text(stringResource(R.string.unlock_save)) }
                if (hasPin) {
                    androidx.compose.material3.TextButton(onClick = {
                        store.pin = null; hasPin = false; pin = ""; revealed = false; show = false
                        enabled = false; prefs.pinUnlockEnabled = false
                    }) { Text(stringResource(R.string.unlock_remove), color = cs.error) }
                }
            }
        }
        RowDivider()
        SettingRow(R.drawable.ic_key, stringResource(R.string.unlock_enable), stringResource(R.string.unlock_enable_desc)) {
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                enabled = hasPin,
                onCheckedChange = { on ->
                    if (!on) { enabled = false; prefs.pinUnlockEnabled = false; return@Switch }
                    val a = activity ?: return@Switch
                    confirmUser(a, authTitle) { enabled = true; prefs.pinUnlockEnabled = true }
                },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Note(stringResource(R.string.unlock_disclaimer))
}
