package work.alberton.droidhand.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient
import work.alberton.droidhand.service.RelayService
import work.alberton.droidhand.shell.ShellBridge
import work.alberton.droidhand.ui.home.HomeScreen
import work.alberton.droidhand.ui.settings.AppearanceScreen
import work.alberton.droidhand.ui.settings.BackgroundScreen
import work.alberton.droidhand.ui.settings.ConnectionScreen
import work.alberton.droidhand.ui.settings.PrivacyScreen
import work.alberton.droidhand.ui.theme.DroidHandTheme
import work.alberton.droidhand.ui.theme.isAppDark

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppConfig.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        if (prefs.serviceEnabled && prefs.relayUrl.isNotBlank()) RelayService.start(this)
        setContent { App(prefs) }
    }
}

private fun readLive(ctx: Context): Live {
    val bg = bgSession()
    return Live(PermissionState.read(ctx), bg?.mode, bg?.displayId ?: -1, ShellBridge.attached)
}

@Composable
private fun App(prefs: Prefs) {
    val ctx = LocalContext.current
    val ui = remember { UiSettings(prefs) }
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    var live by remember { mutableStateOf(readLive(ctx)) }
    val relay by RelayClient.state.collectAsState()

    // Permissions and background state change outside the app: refresh while visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { live = readLive(ctx); delay(1500) }
        }
    }

    DroidHandTheme(ui.theme, ui.dynamic, ui.accent) {
        val dark = isAppDark(ui.theme)
        val view = LocalView.current
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }

        BackHandler(enabled = route != Route.HOME) { route = Route.HOME }
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val forward = targetState != Route.HOME
                (slideInHorizontally { w -> if (forward) w / 6 else -w / 6 } + fadeIn()) togetherWith
                    (slideOutHorizontally { w -> if (forward) -w / 6 else w / 6 } + fadeOut())
            },
            label = "route",
        ) { r ->
            val back = { route = Route.HOME }
            when (r) {
                Route.HOME -> HomeScreen(prefs, ui, live, relay, onOpen = { route = it }, refresh = { live = readLive(ctx) })
                Route.CONNECTION -> ConnectionScreen(prefs, relay, back)
                Route.BACKGROUND -> BackgroundScreen(prefs, live, back)
                Route.APPEARANCE -> AppearanceScreen(ui, back)
                Route.PRIVACY -> PrivacyScreen(prefs, back)
            }
        }
    }
}
