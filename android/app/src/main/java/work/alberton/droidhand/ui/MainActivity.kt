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
import work.alberton.droidhand.adb.AdbSession
import work.alberton.droidhand.ui.home.HomeScreen
import work.alberton.droidhand.ui.home.UpdateModel
import work.alberton.droidhand.ui.settings.AppearanceScreen
import work.alberton.droidhand.ui.settings.BackgroundScreen
import work.alberton.droidhand.ui.settings.ConnectionScreen
import work.alberton.droidhand.ui.settings.PrivacyScreen
import work.alberton.droidhand.ui.settings.AddPhoneScreen
import work.alberton.droidhand.ui.settings.PairAdbScreen
import work.alberton.droidhand.ui.settings.RelayScreen
import work.alberton.droidhand.ui.theme.DroidHandTheme
import work.alberton.droidhand.ui.theme.isAppDark

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppConfig.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        if (prefs.serviceEnabled && prefs.relayUrl.isNotBlank()) RelayService.start(this)
        pendingInvite.value = inviteOf(intent)
        setContent { App(prefs) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        inviteOf(intent)?.let { pendingInvite.value = it }
    }

    /** droidhand://connect#relay=…&token=… (the relay's setup page): asks before using it. */
    private fun inviteOf(i: android.content.Intent?): Invite.Data? =
        i?.data?.takeIf { it.scheme == "droidhand" && it.host == "connect" }?.toString()?.let(Invite::parse)
}

/** An invite opened from a link, waiting for the user to confirm it. */
private val pendingInvite = mutableStateOf<Invite.Data?>(null)

private fun readLive(ctx: Context): Live {
    val bg = bgSession()
    val adbOn = runCatching { android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
    return Live(PermissionState.read(ctx), bg?.mode, bg?.displayId ?: -1, ShellBridge.attached, adbOn, AdbSession.deadline(ctx))
}

@Composable
private fun App(prefs: Prefs) {
    val ctx = LocalContext.current
    val ui = remember { UiSettings(prefs) }
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    var live by remember { mutableStateOf(readLive(ctx)) }
    val relay by RelayClient.state.collectAsState()
    val updates = remember { UpdateModel(ctx) }

    // Permissions and background state change outside the app: refresh while visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { live = readLive(ctx); delay(1500) }
        }
    }
    // Look for a new version every time the app comes to the front (at most once a minute), not only on a cold start.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { updates.check(manual = false) }
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

        pendingInvite.value?.let { inv ->
            // A link can come from any web page: show where it points and let the user decide.
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingInvite.value = null },
                title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(work.alberton.droidhand.R.string.link_title)) },
                text = {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(
                        if (prefs.relayUrl.isBlank()) work.alberton.droidhand.R.string.link_body
                        else work.alberton.droidhand.R.string.link_body_replace, inv.relay, inv.name ?: "—"))
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        prefs.relayUrl = inv.relay; prefs.token = inv.token
                        if (prefs.serviceEnabled) RelayService.restart(ctx)
                        pendingInvite.value = null
                        route = Route.CONNECTION
                    }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(work.alberton.droidhand.R.string.link_use)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pendingInvite.value = null }) {
                        androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(android.R.string.cancel))
                    }
                },
            )
        }
        BackHandler(enabled = route != Route.HOME) { route = if (route == Route.ADD_PHONE) Route.RELAY else Route.HOME }
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally { w -> if (forward) w / 6 else -w / 6 } + fadeIn()) togetherWith
                    (slideOutHorizontally { w -> if (forward) -w / 6 else w / 6 } + fadeOut())
            },
            label = "route",
        ) { r ->
            val back = { route = Route.HOME }
            when (r) {
                Route.HOME -> HomeScreen(prefs, ui, live, relay, updates, onOpen = { route = it }, refresh = { live = readLive(ctx) })
                Route.CONNECTION -> ConnectionScreen(prefs, relay, back)
                Route.RELAY -> RelayScreen(relay, back, onAddPhone = { route = Route.ADD_PHONE })
                Route.ADD_PHONE -> AddPhoneScreen(prefs, relay, onBack = { route = Route.RELAY })
                Route.PAIR_ADB -> PairAdbScreen(relay, live, back, onConnection = { route = Route.CONNECTION })
                Route.BACKGROUND -> BackgroundScreen(prefs, live, back)
                Route.APPEARANCE -> AppearanceScreen(ui, back)
                Route.PRIVACY -> PrivacyScreen(prefs, back)
            }
        }
    }
}
