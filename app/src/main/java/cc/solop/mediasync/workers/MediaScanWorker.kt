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
            val candidates = services.mediaStoreScanner.scanNewMedia(lastScan, BATCH_SIZE)
            candidates.forEach { WorkScheduler.enqueueUpload(applicationContext, it) }

            if (candidates.isNotEmpty()) {
                statusRepo.incrementQueued(candidates.size.toLong())
                val maxSeenDateAdded = candidates.maxOf { it.dateAddedEpochMs }
                // Step back 1s because MediaStore query uses seconds precision.
                val nextWatermark = (maxSeenDateAdded - 1000L).coerceAtLeast(0L)
                statusRepo.setLastScanEpochMs(nextWatermark)
                if (candidates.size == BATCH_SIZE) {
                    WorkScheduler.enqueueScanNow(applicationContext)
                }
            } else {
                statusRepo.setLastScanEpochMs(System.currentTimeMillis())
            }
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private companion object {
        const val BATCH_SIZE = 100
    }
}
