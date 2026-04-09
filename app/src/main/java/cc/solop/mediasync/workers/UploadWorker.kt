package cc.solop.mediasync.workers

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import cc.solop.mediasync.data.media.MediaCandidate
import cc.solop.mediasync.di.ServiceLocator
import cc.solop.mediasync.domain.PermanentUploadException
import cc.solop.mediasync.domain.RetryableUploadException
import cc.solop.mediasync.domain.UploadResult

class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val candidate = readCandidate(inputData) ?: return Result.failure()
        val services = ServiceLocator.from(applicationContext)
        val statusRepo = services.syncStatusRepository
        val token = services.tokenStore.getCachedToken().trim()

        if (token.isBlank()) {
            Log.e(TAG, "Upload failed: missing token, uri=${candidate.uri}")
            statusRepo.incrementFailed(candidate.uri, "Missing auth token. Save token and tap Retry Failed.")
            return Result.failure()
        }

        return try {
            Log.d(TAG, "Starting upload, uri=${candidate.uri}, attempt=${runAttemptCount + 1}")
            when (val result = services.uploadOrchestrator.upload(candidate)) {
                is UploadResult.Uploaded -> statusRepo.incrementUploaded(candidate.uri, result.key)
                UploadResult.Duplicate -> statusRepo.incrementDuplicate()
            }
            Log.d(TAG, "Upload success, uri=${candidate.uri}")
            Result.success()
        } catch (e: RetryableUploadException) {
            Log.w(TAG, "Retryable upload failure, uri=${candidate.uri}, attempt=${runAttemptCount + 1}: ${e.message}", e)
            if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
                statusRepo.incrementFailed(candidate.uri, "Retry limit reached: ${e.message ?: "Unknown error"}")
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (e: PermanentUploadException) {
            Log.e(TAG, "Permanent upload failure, uri=${candidate.uri}: ${e.message}", e)
            statusRepo.incrementFailed(candidate.uri, e.message ?: "Permanent failure")
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected upload failure, uri=${candidate.uri}: ${e.message}", e)
            if (runAttemptCount >= MAX_RETRY_ATTEMPTS) {
                statusRepo.incrementFailed(candidate.uri, "Unexpected error: ${e.message ?: "Unknown error"}")
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val KEY_URI = "uri"
        private const val KEY_FILENAME = "filename"
        private const val KEY_MIME_TYPE = "mimeType"
        private const val KEY_CAPTURED_AT = "capturedAt"
        private const val KEY_SIZE_BYTES = "sizeBytes"

        fun inputData(candidate: MediaCandidate): Data {
            return Data.Builder()
                .putString(KEY_URI, candidate.uri)
                .putString(KEY_FILENAME, candidate.filename)
                .putString(KEY_MIME_TYPE, candidate.mimeType)
                .putString(KEY_CAPTURED_AT, candidate.capturedAtIso)
                .putLong(KEY_SIZE_BYTES, candidate.sizeBytes)
                .build()
        }

        private fun readCandidate(data: Data): MediaCandidate? {
            val uri = data.getString(KEY_URI) ?: return null
            val filename = data.getString(KEY_FILENAME) ?: return null
            val mimeType = data.getString(KEY_MIME_TYPE) ?: return null
            val capturedAtIso = data.getString(KEY_CAPTURED_AT) ?: return null
            val sizeBytes = data.getLong(KEY_SIZE_BYTES, -1)
            if (sizeBytes < 0) return null

            return MediaCandidate(
                uri = uri,
                filename = filename,
                mimeType = mimeType,
                capturedAtIso = capturedAtIso,
                sizeBytes = sizeBytes,
            )
        }
    }
}
