package work.alberton.droidhand.ui.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import work.alberton.droidhand.R
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.ui.AppConfig
import work.alberton.droidhand.ui.UiSettings
import work.alberton.droidhand.ui.components.Card
import work.alberton.droidhand.ui.components.Page
import work.alberton.droidhand.ui.components.RowDivider
import work.alberton.droidhand.ui.components.SectionLabel
import work.alberton.droidhand.ui.components.SettingRow
import work.alberton.droidhand.ui.theme.Accent

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

/** One line of text that shrinks its font until it fits (e.g. "Português" in a narrow segment). */
@Composable
private fun FitText(text: String) {
    val base = MaterialTheme.typography.labelLarge
    var size by remember(text) { mutableStateOf(base.fontSize) }
    var ready by remember(text) { mutableStateOf(false) }
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        style = base.copy(fontSize = size),
        modifier = Modifier.drawWithContent { if (ready) drawContent() },
        onTextLayout = { r ->
            if (r.didOverflowWidth && size.value > 9f) size = (size.value * 0.92f).sp else ready = true
        },
    )
}
