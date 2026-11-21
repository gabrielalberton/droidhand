package work.alberton.droidhand.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import work.alberton.droidhand.R
import work.alberton.droidhand.data.Prefs

/** Accent choices for our own palette (ignored when wallpaper colors are on). */
enum class Accent(val key: String, val label: Int, val light: Color, val dark: Color) {
    INDIGO("indigo", R.string.accent_indigo, Color(0xFF4F46E5), Color(0xFFA5B4FC)),
    TEAL("teal", R.string.accent_teal, Color(0xFF0F766E), Color(0xFF5EEAD4)),
    AMBER("amber", R.string.accent_amber, Color(0xFFB45309), Color(0xFFFCD34D)),
    ROSE("rose", R.string.accent_rose, Color(0xFFBE123C), Color(0xFFFDA4AF)),
    GRAPHITE("graphite", R.string.accent_graphite, Color(0xFF1F2937), Color(0xFFE5E7EB));

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: INDIGO
    }
}

/** Status colors that Material does not provide. */
@Immutable
data class StatusColors(val ok: Color, val okContainer: Color, val warn: Color, val warnContainer: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(Color(0xFF15803D), Color(0xFFDCFCE7), Color(0xFFB45309), Color(0xFFFEF3C7))
}

private fun lightScheme(a: Color): ColorScheme {
    val bg = Color(0xFFF6F7F9)
    return lightColorScheme(
        primary = a, onPrimary = Color.White,
        primaryContainer = lerp(Color.White, a, 0.12f), onPrimaryContainer = lerp(a, Color.Black, 0.35f),
        secondary = Color(0xFF5D636E), onSecondary = Color.White,
        secondaryContainer = lerp(Color.White, a, 0.08f), onSecondaryContainer = lerp(a, Color.Black, 0.45f),
        tertiary = a,
        background = bg, onBackground = Color(0xFF14161A),
        surface = bg, onSurface = Color(0xFF14161A),
        surfaceVariant = Color(0xFFEDEFF3), onSurfaceVariant = Color(0xFF5D636E),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
        surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF0F2F5),
        surfaceContainerHighest = Color(0xFFE8EBEF),
        outline = Color(0xFFC4C8D0), outlineVariant = Color(0xFFE3E6EB),
        error = Color(0xFFDC2626), onError = Color.White,
        errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF7F1D1D),
    )
}

private fun darkScheme(a: Color): ColorScheme {
    val bg = Color(0xFF0E0F13)
    return darkColorScheme(
        primary = a, onPrimary = Color(0xFF0E0F13),
        primaryContainer = lerp(bg, a, 0.20f), onPrimaryContainer = a,
        secondary = Color(0xFFA3A7B0), onSecondary = bg,
        secondaryContainer = lerp(bg, a, 0.12f), onSecondaryContainer = lerp(a, Color.White, 0.4f),
        tertiary = a,
        background = bg, onBackground = Color(0xFFE7E8EB),
        surface = bg, onSurface = Color(0xFFE7E8EB),
        surfaceVariant = Color(0xFF1D2026), onSurfaceVariant = Color(0xFFA3A7B0),
        surfaceContainerLowest = Color(0xFF0A0B0E), surfaceContainerLow = Color(0xFF15171C),
        surfaceContainer = Color(0xFF17191E), surfaceContainerHigh = Color(0xFF1E2127),
        surfaceContainerHighest = Color(0xFF262930),
        outline = Color(0xFF454952), outlineVariant = Color(0xFF2A2D34),
        error = Color(0xFFF87171), onError = Color(0xFF450A0A),
        errorContainer = Color(0xFF3B1214), onErrorContainer = Color(0xFFFECACA),
    )
}

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
    )
}

/** Resolves the app's dark/light choice (the system one unless overridden). */
@Composable
fun isAppDark(themeMode: String): Boolean = when (themeMode) {
    Prefs.THEME_LIGHT -> false
    Prefs.THEME_DARK -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun DroidHandTheme(themeMode: String, dynamic: Boolean, accent: Accent, content: @Composable () -> Unit) {
    val dark = isAppDark(themeMode)
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)).let {
                // Keep our calm neutral background even with wallpaper colors.
                if (dark) it else it.copy(background = Color(0xFFF6F7F9), surface = Color(0xFFF6F7F9))
            }
        dark -> darkScheme(accent.dark)
        else -> lightScheme(accent.light)
    }
    val status = if (dark) {
        StatusColors(Color(0xFF4ADE80), Color(0xFF12301E), Color(0xFFFBBF24), Color(0xFF33260C))
    } else {
        StatusColors(Color(0xFF15803D), Color(0xFFDCFCE7), Color(0xFFB45309), Color(0xFFFEF3C7))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
