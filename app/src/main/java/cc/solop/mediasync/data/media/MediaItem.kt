package cc.solop.mediasync.data.media

import java.security.MessageDigest

data class MediaCandidate(
    val uri: String,
    val filename: String,
    val mimeType: String,
    val capturedAtIso: String,
    val sizeBytes: Long,
    val dateAddedEpochMs: Long = 0L,
) {
    val stableId: String
        get() {
            val seed = "$uri|$sizeBytes|$capturedAtIso"
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(24)
        }
}
