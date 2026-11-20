package work.alberton.droidhand

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class DroidHandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "droidhand_status"
    }
}
