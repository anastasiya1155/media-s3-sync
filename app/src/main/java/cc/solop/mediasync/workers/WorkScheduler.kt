package cc.solop.mediasync.workers

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cc.solop.mediasync.data.media.MediaCandidate
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val PERIODIC_SCAN_WORK_NAME = "periodic_media_scan"
    const val TAG_SCAN = "media_scan"
    const val TAG_UPLOAD = "media_upload"

    fun schedulePeriodicScan(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<MediaScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .addTag(TAG_SCAN)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SCAN_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueScanNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MediaScanWorker>()
            .addTag(TAG_SCAN)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    fun enqueueUpload(context: Context, candidate: MediaCandidate) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG_UPLOAD)
            .setInputData(UploadWorker.inputData(candidate))
            .build()

        val uniqueName = "UPLOAD_${candidate.stableId}"
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
    }

    fun stopAllSync(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_SCAN_WORK_NAME)
        workManager.cancelAllWorkByTag(TAG_SCAN)
        workManager.cancelAllWorkByTag(TAG_UPLOAD)
    }

    fun clearQueue(context: Context) {
        val workManager = WorkManager.getInstance(context)
        // Keep periodic schedule untouched; only purge currently queued/running work.
        workManager.cancelAllWorkByTag(TAG_SCAN)
        workManager.cancelAllWorkByTag(TAG_UPLOAD)
    }

    fun resumeSync(context: Context) {
        schedulePeriodicScan(context)
        enqueueScanNow(context)
    }
}
