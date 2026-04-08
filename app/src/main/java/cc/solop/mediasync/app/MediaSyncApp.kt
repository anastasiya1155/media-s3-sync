package cc.solop.mediasync.app

import android.app.Application
import cc.solop.mediasync.di.ServiceLocator
import cc.solop.mediasync.workers.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MediaSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()

        WorkScheduler.schedulePeriodicScan(this)

        CoroutineScope(Dispatchers.IO).launch {
            ServiceLocator.from(this@MediaSyncApp).tokenStore.warmup()
        }
    }
}
