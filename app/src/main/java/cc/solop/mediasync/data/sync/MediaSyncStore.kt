package cc.solop.mediasync.data.sync

import cc.solop.mediasync.data.media.MediaCandidate

class MediaSyncStore(private val dao: MediaSyncDao) {
    data class ItemStatus(
        val state: MediaSyncState,
        val detail: String?,
    )

    data class SyncOverview(
        val pending: Int = 0,
        val syncing: Int = 0,
        val synced: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
        val unknown: Int = 0,
    )

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
                    lastError = "Pending upload",
                    updatedAtMs = now,
                )
            }
        )
    }

    suspend fun markState(uri: String, state: MediaSyncState, lastError: String? = null) {
        val now = System.currentTimeMillis()
        val updated = dao.updateState(
            uri = uri,
            state = state.name,
            lastError = lastError,
            updatedAtMs = now,
        )
        if (updated == 0) {
            dao.upsert(
                listOf(
                    MediaSyncItemEntity(
                        uri = uri,
                        state = state.name,
                        filename = uri.substringAfterLast('/').ifBlank { "media" },
                        mimeType = "application/octet-stream",
                        capturedAtIso = "",
                        sizeBytes = 0L,
                        lastError = lastError,
                        updatedAtMs = now,
                    )
                )
            )
        }
    }

    suspend fun getStateMap(uris: List<String>): Map<String, MediaSyncState> {
        if (uris.isEmpty()) return emptyMap()
        return dao.getByUris(uris).associate { row ->
            row.uri to runCatching { MediaSyncState.valueOf(row.state) }.getOrDefault(MediaSyncState.PENDING)
        }
    }

    suspend fun getStatusMap(uris: List<String>): Map<String, ItemStatus> {
        if (uris.isEmpty()) return emptyMap()
        return dao.getByUris(uris).associate { row ->
            val parsedState = runCatching { MediaSyncState.valueOf(row.state) }.getOrDefault(MediaSyncState.PENDING)
            row.uri to ItemStatus(
                state = parsedState,
                detail = row.lastError?.takeIf { it.isNotBlank() },
            )
        }
    }

    suspend fun getPendingUris(limit: Int): List<String> {
        return dao.getUrisByState(MediaSyncState.PENDING.name, limit)
    }

    suspend fun getResumableUris(limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        val activeStates = listOf(MediaSyncState.PENDING.name, MediaSyncState.SYNCING.name)
        val known = dao.getUrisByStates(activeStates, limit)
        if (known.size >= limit) return known
        val unknown = dao.getUrisWithUnknownState(limit - known.size)
        return (known + unknown).distinct().take(limit)
    }

    suspend fun getFailedUris(limit: Int): List<String> {
        return dao.getUrisByState(MediaSyncState.FAILED.name, limit)
    }

    suspend fun getOverview(): SyncOverview {
        return SyncOverview(
            pending = dao.countByState(MediaSyncState.PENDING.name),
            syncing = dao.countByState(MediaSyncState.SYNCING.name),
            synced = dao.countByState(MediaSyncState.SYNCED.name),
            failed = dao.countByState(MediaSyncState.FAILED.name),
            skipped = dao.countByState(MediaSyncState.SKIPPED.name),
            unknown = dao.countUnknownState(),
        )
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}
