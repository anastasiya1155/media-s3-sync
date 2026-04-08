package cc.solop.mediasync.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cc.solop.mediasync.di.ServiceLocator

class MediaScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val services = ServiceLocator.from(applicationContext)
        val statusRepo = services.syncStatusRepository

        return try {
            val lastScan = statusRepo.getLastScanEpochMs()
            val candidates = services.mediaStoreScanner.scanNewMedia(lastScan)
            candidates.forEach { WorkScheduler.enqueueUpload(applicationContext, it) }

            if (candidates.isNotEmpty()) {
                statusRepo.incrementQueued(candidates.size.toLong())
            }
            statusRepo.setLastScanEpochMs(System.currentTimeMillis())
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
