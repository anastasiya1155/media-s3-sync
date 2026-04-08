package cc.solop.mediasync.domain

import android.content.ContentResolver
import android.net.Uri
import cc.solop.mediasync.data.api.MediaActionRequest
import cc.solop.mediasync.data.api.MediaApi
import cc.solop.mediasync.data.media.Hashing
import cc.solop.mediasync.data.media.MediaCandidate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import retrofit2.HttpException
import java.io.IOException

sealed class UploadResult {
    data object Uploaded : UploadResult()
    data object Duplicate : UploadResult()
}

class RetryableUploadException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PermanentUploadException(message: String, cause: Throwable? = null) : Exception(message, cause)

class UploadOrchestrator(
    private val mediaApi: MediaApi,
    private val contentResolver: ContentResolver,
    private val okHttpClient: OkHttpClient,
) {
    suspend fun upload(candidate: MediaCandidate): UploadResult {
        val uri = Uri.parse(candidate.uri)
        val sha256 = runCatching { Hashing.sha256(contentResolver, uri) }
            .getOrElse { throw PermanentUploadException("Cannot hash media: ${it.message}", it) }

        val initResponse = try {
            mediaApi.postMediaAction(
                MediaActionRequest(
                    action = "init-upload",
                    filename = candidate.filename,
                    mimeType = candidate.mimeType,
                    capturedAt = candidate.capturedAtIso,
                    sizeBytes = candidate.sizeBytes,
                    sha256 = sha256,
                )
            )
        } catch (e: Exception) {
            throw classifyApiException("init-upload failed", e)
        }

        if (initResponse.duplicate == true) {
            return UploadResult.Duplicate
        }

        val uploadUrl = initResponse.uploadUrl ?: throw PermanentUploadException("Missing uploadUrl from init-upload")
        val key = initResponse.key ?: throw PermanentUploadException("Missing key from init-upload")

        try {
            uploadToSignedUrl(uploadUrl, uri, candidate.mimeType)
        } catch (e: Exception) {
            failUploadSafely(key, "upload-put-failed: ${e.message}")
            throw classifyNetworkException("PUT upload failed", e)
        }

        try {
            mediaApi.postMediaAction(
                MediaActionRequest(
                    action = "complete-upload",
                    key = key,
                    mimeType = candidate.mimeType,
                    capturedAt = candidate.capturedAtIso,
                    sizeBytes = candidate.sizeBytes,
                    sha256 = sha256,
                )
            )
        } catch (e: Exception) {
            failUploadSafely(key, "complete-upload-failed: ${e.message}")
            throw classifyApiException("complete-upload failed", e)
        }

        return UploadResult.Uploaded
    }

    private fun uploadToSignedUrl(uploadUrl: String, uri: Uri, mimeType: String) {
        val requestBody = object : RequestBody() {
            override fun contentType() = mimeType.toMediaType()

            override fun writeTo(sink: BufferedSink) {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot open stream for $uri" }
                    sink.writeAll(input.source())
                }
            }
        }

        val request = Request.Builder()
            .url(uploadUrl)
            .put(requestBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("PUT failed with code ${response.code}")
            }
        }
    }

    private suspend fun failUploadSafely(key: String, reason: String) {
        runCatching {
            mediaApi.postMediaAction(
                MediaActionRequest(
                    action = "fail-upload",
                    key = key,
                    reason = reason.take(500),
                )
            )
        }
    }

    private fun classifyApiException(prefix: String, throwable: Throwable): Exception {
        return when (throwable) {
            is HttpException -> {
                if (throwable.code() >= 500 || throwable.code() == 429) {
                    RetryableUploadException("$prefix (${throwable.code()})", throwable)
                } else {
                    PermanentUploadException("$prefix (${throwable.code()})", throwable)
                }
            }
            is IOException -> RetryableUploadException(prefix, throwable)
            else -> PermanentUploadException(prefix, throwable)
        }
    }

    private fun classifyNetworkException(prefix: String, throwable: Throwable): Exception {
        return when (throwable) {
            is IOException -> RetryableUploadException(prefix, throwable)
            else -> PermanentUploadException(prefix, throwable)
        }
    }
}
