package work.alberton.droidhand.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import work.alberton.droidhand.R
import work.alberton.droidhand.a11y.DroidHandAccessibilityService
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.ui.MainActivity

/**
 * Quick Settings tile: a kill switch one swipe away. While the agent is on,
 * tapping it disconnects the agent (same as the notification's Disconnect);
 * while it is off, tapping opens the app to turn it back on.
 */
class KillSwitchTile : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        if (Prefs(this).serviceEnabled) {
            DroidHandAccessibilityService.instance?.stopAgent() ?: RelayService.stop(this)
            render(enabled = false)
        } else {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION") startActivityAndCollapse(intent)
            }
        }
    }

    private fun render(enabled: Boolean = Prefs(this).serviceEnabled) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(if (enabled) R.string.tile_on else R.string.tile_off)
        tile.updateTile()
    }

    companion object {
        /** Ask the system to redraw the tile after the agent was started or stopped elsewhere. */
        fun refresh(context: Context) = runCatching {
            requestListeningState(context, ComponentName(context, KillSwitchTile::class.java))
        }
    }
}
