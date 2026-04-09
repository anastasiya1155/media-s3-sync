package cc.solop.mediasync.app

import android.app.Application
import cc.solop.mediasync.workers.WorkScheduler

class MediaSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()

        WorkScheduler.schedulePeriodicScan(this)
    }
}
