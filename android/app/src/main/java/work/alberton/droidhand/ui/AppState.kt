package work.alberton.droidhand.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import work.alberton.droidhand.a11y.DroidHandAccessibilityService
import work.alberton.droidhand.bg.BackgroundSession
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.ui.theme.Accent

/** Navigation routes of the app. */
enum class Route { HOME, CONNECTION, RELAY, BACKGROUND, APPEARANCE, PRIVACY, ADD_PHONE, PAIR_ADB }

/** Live, non-persisted device state shown by the UI. */
data class Live(
    val perms: PermissionState,
    val bgMode: BackgroundSession.Mode? = null,
    val bgDisplay: Int = -1,
    val helper: Boolean = false,
    /** USB debugging is on right now, and until when (0 = no session of ours, e.g. turned on by hand). */
    val adbOn: Boolean = false,
    val adbDeadline: Long = 0L,
)

/** UI-side copy of the settings, so changes recompose immediately. */
class UiSettings(val prefs: Prefs) {
    var theme by mutableStateOf(prefs.themeMode)
    var dynamic by mutableStateOf(prefs.dynamicColor)
    var accent by mutableStateOf(Accent.of(prefs.accent))
}

fun bgSession(): BackgroundSession? =
    DroidHandAccessibilityService.instance?.let { s -> runCatching { s.bg }.getOrNull() }
