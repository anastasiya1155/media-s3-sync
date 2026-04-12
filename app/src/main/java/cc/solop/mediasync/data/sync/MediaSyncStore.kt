package cc.solop.mediasync.data.sync

import cc.solop.mediasync.data.media.MediaCandidate

class MediaSyncStore(private val dao: MediaSyncDao) {

    suspend fun markPending(candidates: List<MediaCandidate>) {
        if (candidates.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.upsert(
            candidates.map {
                MediaSyncItemEntity(
                    uri = it.uri,
                    state = MediaSyncState.PENDING.name,
                    filename = it.filename,
                    mimeType = it.mimeType,
                    capturedAtIso = it.capturedAtIso,
                    sizeBytes = it.sizeBytes,
                    lastError = null,
                    updatedAtMs = now,
                )
            }
        )
    }

    suspend fun markState(uri: String, state: MediaSyncState, lastError: String? = null) {
        dao.updateState(
            uri = uri,
            state = state.name,
            lastError = lastError,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    suspend fun getStateMap(uris: List<String>): Map<String, MediaSyncState> {
        if (uris.isEmpty()) return emptyMap()
        return dao.getByUris(uris).associate { row ->
            row.uri to runCatching { MediaSyncState.valueOf(row.state) }.getOrDefault(MediaSyncState.PENDING)
        }
    }

    suspend fun getPendingUris(limit: Int): List<String> {
        return dao.getUrisByState(MediaSyncState.PENDING.name, limit)
    }

    suspend fun getFailedUris(limit: Int): List<String> {
        return dao.getUrisByState(MediaSyncState.FAILED.name, limit)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}
