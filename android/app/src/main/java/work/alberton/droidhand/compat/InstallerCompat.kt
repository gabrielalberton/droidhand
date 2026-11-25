package work.alberton.droidhand.compat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import work.alberton.droidhand.BuildConfig
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient

/**
 * "Compatibility with banking apps". Banks (and Google's Play Integrity) treat an accessibility
 * service from an app that was NOT installed by the Play Store as a threat; the same service from
 * a Play-installed app is fine (tested with Nubank on a Galaxy S24, see CHANGELOG 1.3.0). Android
 * records the installer of every app, and an update through the system installer (or this app's
 * own updater) resets it. When the option is on, the app asks the relay to reinstall the APK in
 * place with the Play Store recorded as installer (`adb install -r -i com.android.vending`; the
 * relay turns debugging on for it and off again). Nothing is downloaded or changed in the APK.
 *
 * This makes Android report a different installer than the real one. The option is ON by default
 * and the app says so plainly (Privacy & safety).
 */
object InstallerCompat {
    const val PLAY_STORE = "com.android.vending"
    private const val RETRY_MS = 10 * 60_000L
    private const val MAX_TRIES = 3

    sealed interface Status {
        data object Off : Status
        data object Active : Status
        data object Pending : Status
        data object Working : Status
        data class Failed(val message: String) : Status
    }

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Installer package Android has on record for this app (null = none, e.g. adb without -i). */
    fun installer(ctx: Context): String? = runCatching {
        ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
    }.getOrNull()

    fun isPlay(ctx: Context) = installer(ctx) == PLAY_STORE

    fun status(ctx: Context, prefs: Prefs, working: Boolean, error: String?): Status = when {
        !prefs.playInstaller -> Status.Off
        isPlay(ctx) -> Status.Active
        working -> Status.Working
        error != null -> Status.Failed(error)
        else -> Status.Pending
    }

    /** Called when the relay connection is up (and from the "apply now" button with [force]). */
    fun maybeFix(ctx: Context, relay: RelayClient, force: Boolean = false) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (isPlay(app)) { if (prefs.installerFixTries != 0) prefs.installerFixTries = 0; return }
        if (!prefs.playInstaller || _working.value) return
        if (!force) {
            val sinceLast = System.currentTimeMillis() - prefs.installerFixAt
            if (prefs.installerFixTries >= MAX_TRIES || sinceLast < RETRY_MS) return
        }
        _working.value = true
        _lastError.value = null
        prefs.noteInstallerFix(BuildConfig.VERSION_NAME)
        scope.launch {
            // The relay reinstalls this very app, so on success the process is killed before any answer arrives.
            val res = runCatching { relay.request("fix_installer", null, 180_000) }.getOrNull()
            _working.value = false
            if (res != null && !res.optBoolean("ok", true)) _lastError.value = res.optString("error").ifBlank { "unknown error" }
        }
    }
}
