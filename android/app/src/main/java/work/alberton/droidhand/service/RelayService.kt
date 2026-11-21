package work.alberton.droidhand.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import work.alberton.droidhand.DroidHandApp
import work.alberton.droidhand.R
import work.alberton.droidhand.relay.RelayClient
import work.alberton.droidhand.ui.MainActivity

/**
 * Foreground service that keeps the relay connection alive and shows the always-
 * visible "Agent connected" notification with a Disconnect (kill-switch) action.
 */
class RelayService : Service() {
    private var relay: RelayClient? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            work.alberton.droidhand.data.Prefs(this).serviceEnabled = false
            // Same effect from every kill switch (notification, tile, panic): nothing of the agent stays on screen.
            work.alberton.droidhand.a11y.DroidHandAccessibilityService.instance?.clearAgentUi()
            relay?.stop()
            relay = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            KillSwitchTile.refresh(this)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            relay?.stop()
            relay = null
            if (!work.alberton.droidhand.data.Prefs(this).serviceEnabled) { stopSelf(); return START_NOT_STICKY }
        }
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (relay == null) {
            relay = RelayClient(this).also { it.start() }
        }
        KillSwitchTile.refresh(this)
        return START_STICKY
    }

    override fun onDestroy() {
        relay?.stop()
        relay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DroidHandApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_connected))
            .setSmallIcon(R.drawable.ic_stat_droidhand)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_disconnect), stop)
            .build()
    }

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "work.alberton.droidhand.STOP"
        const val ACTION_RESTART = "work.alberton.droidhand.RESTART"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RelayService::class.java))
        }

        /** Reconnect with the current settings (e.g. after editing the address or token). */
        fun restart(context: Context) {
            context.startService(Intent(context, RelayService::class.java).setAction(ACTION_RESTART))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RelayService::class.java).setAction(ACTION_STOP))
        }
    }
}
