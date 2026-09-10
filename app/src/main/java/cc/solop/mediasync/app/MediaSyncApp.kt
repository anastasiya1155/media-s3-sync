package cc.solop.mediasync.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import cc.solop.mediasync.workers.WorkScheduler

class MediaSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()
        WorkScheduler.schedulePeriodicScan(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val uploadChannel = NotificationChannel(
            UPLOAD_NOTIFICATION_CHANNEL_ID,
            "Media uploads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while media is syncing in the background."
            setShowBadge(false)
        }
        manager.createNotificationChannel(uploadChannel)
    }

    companion object {
        const val UPLOAD_NOTIFICATION_CHANNEL_ID = "upload_sync"
    }
}
