package work.alberton.droidhand.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.ui.AppConfig

/**
 * Configure the app over adb, without touching the screen (e.g. a fresh install
 * on a locked phone). Only senders holding WRITE_SECURE_SETTINGS can reach it —
 * in practice the adb shell (see the manifest) — so other apps cannot change the
 * relay address or token.
 *
 *   adb shell am broadcast -n work.alberton.droidhand/.service.ProvisionReceiver \
 *     --es relay ws://100.x.y.z:8787 --es token <token> [--ez enable true] \
 *     [--ez suggestions true] [--ez askMainScreen true] [--es bgMode agent|native|own] \
 *     [--es theme system|light|dark] [--es accent indigo] [--ez dynamicColor false] [--es language pt-BR]
 */
class ProvisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val p = Prefs(context)
        val changed = mutableListOf<String>()
        intent.getStringExtra("relay")?.let { p.relayUrl = it.trim(); changed += "relay" }
        intent.getStringExtra("token")?.let { p.token = it.trim(); changed += "token" }
        if (intent.hasExtra("suggestions")) { p.suggestionLogging = intent.getBooleanExtra("suggestions", false); changed += "suggestions" }
        if (intent.hasExtra("askMainScreen")) { p.askMainScreen = intent.getBooleanExtra("askMainScreen", true); changed += "askMainScreen" }
        intent.getStringExtra("bgMode")?.takeIf { it in setOf(Prefs.BG_AGENT, Prefs.BG_NATIVE, Prefs.BG_OWN) }
            ?.let { p.bgMode = it; changed += "bgMode" }
        intent.getStringExtra("theme")?.takeIf { it in setOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK) }
            ?.let { p.themeMode = it; AppConfig.applyNightMode(context, it); changed += "theme" }
        intent.getStringExtra("accent")?.let { p.accent = it; changed += "accent" }
        if (intent.hasExtra("dynamicColor")) { p.dynamicColor = intent.getBooleanExtra("dynamicColor", false); changed += "dynamicColor" }
        intent.getStringExtra("language")?.let { AppConfig.setLanguage(context, it); changed += "language" }
        if (intent.getBooleanExtra("enable", false) && p.relayUrl.isNotBlank()) { p.serviceEnabled = true; changed += "enable" }
        // Never log values (the token is a secret).
        Log.i("DroidHand", "provisioned: ${changed.joinToString()}")
        resultData = "provisioned: ${changed.joinToString()}"
        if (p.serviceEnabled) runCatching { RelayService.restart(context) }
    }
}
