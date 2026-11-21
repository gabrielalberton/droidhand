package work.alberton.droidhand.ui

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import work.alberton.droidhand.data.Prefs
import java.util.Locale

/**
 * Applies the appearance/language choices at the system level where Android
 * supports it, so the splash screen, notifications and floating controls follow
 * them too (not only the Compose UI).
 */
object AppConfig {
    /** Android 12+: per-app night mode (persisted by the system). */
    fun applyNightMode(ctx: Context, mode: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ui = ctx.getSystemService(UiModeManager::class.java) ?: return
        ui.setApplicationNightMode(
            when (mode) {
                Prefs.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO
                Prefs.THEME_DARK -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO
            },
        )
    }

    /** Current language tag: "" (system), "en" or "pt-BR". */
    fun language(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales?.let {
                if (it.isEmpty) "" else it[0].toLanguageTag()
            } ?: ""
        } else {
            Prefs(ctx).language
        }

    /** Android 13+ restarts the activity by itself; older versions need [wrap] + recreate(). */
    fun setLanguage(ctx: Context, tag: String) {
        Prefs(ctx).language = tag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }

    /** Pre-Android-13 language override for an activity's base context. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = Prefs(base).language
        if (tag.isEmpty()) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(cfg)
    }
}
