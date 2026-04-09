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
            val cursor = statusRepo.getScanCursor()
            val candidates = services.mediaStoreScanner.scanNewMedia(
                sinceDateAddedSec = cursor.dateAddedSec,
                sinceMediaIdExclusive = cursor.mediaIdExclusive,
                limit = BATCH_SIZE,
            )
            candidates.forEach { WorkScheduler.enqueueUpload(applicationContext, it) }

            if (candidates.isNotEmpty()) {
                statusRepo.incrementQueued(candidates.size.toLong())
                val last = candidates.last()
                statusRepo.setScanCursor(
                    dateAddedSec = last.dateAddedEpochMs / 1000L,
                    mediaIdExclusive = last.mediaId,
                )
                if (candidates.size == BATCH_SIZE) {
                    WorkScheduler.enqueueScanNow(applicationContext)
                }
            }
            Result.success()
        } catch (_: SecurityException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private companion object {
        const val BATCH_SIZE = 1
    }
}
