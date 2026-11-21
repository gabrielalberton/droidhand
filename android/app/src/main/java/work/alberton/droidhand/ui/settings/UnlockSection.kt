package work.alberton.droidhand.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.R
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.data.SecretStore
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.Note
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.components.SectionLabel
import work.alberton.droidhand.ui.components.SettingRow
import work.alberton.droidhand.ui.copy

/** Asks for the user's fingerprint/face or device PIN; the agent cannot pass this. */
fun confirmUser(activity: Activity, title: String, onOk: () -> Unit) {
    val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
        .setTitle(title)
        .setAllowedAuthenticators(
            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        .build()
    try {
        prompt.authenticate(
            android.os.CancellationSignal(), activity.mainExecutor,
            object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) = onOk()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    val cancelled = errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    if (!cancelled && errString != null) android.widget.Toast.makeText(activity, errString, android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )
    } catch (e: Exception) {
        // Never crash the app over the confirmation dialog.
        android.widget.Toast.makeText(activity, e.message ?: e.javaClass.simpleName, android.widget.Toast.LENGTH_LONG).show()
    }
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
