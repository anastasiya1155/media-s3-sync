package cc.solop.mediasync.data.sync

import cc.solop.mediasync.data.media.MediaCandidate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncStoreTest {

    @Test
    fun markPending_setsPendingStateAndDefaultDetail() = runBlocking {
        val dao = FakeMediaSyncDao()
        val store = MediaSyncStore(dao)
        val candidate = candidate(uri = "content://media/external/images/1")

        store.markPending(listOf(candidate))

        val status = store.getStatusMap(listOf(candidate.uri))[candidate.uri]
        checkNotNull(status)
        assertEquals(MediaSyncState.PENDING, status.state)
        assertEquals("Pending upload", status.detail)
    }

    @Test
    fun markState_upsertsWhenRowMissing() = runBlocking {
        val dao = FakeMediaSyncDao()
        val store = MediaSyncStore(dao)

        store.markState(
            uri = "content://media/external/images/2",
            state = MediaSyncState.SYNCED,
            lastError = "Uploaded",
        )

        val status = store.getStatusMap(listOf("content://media/external/images/2"))["content://media/external/images/2"]
        checkNotNull(status)
        assertEquals(MediaSyncState.SYNCED, status.state)
        assertEquals("Uploaded", status.detail)
    }

    @Test
    fun getResumableUris_includesPendingSyncingAndUnknown() = runBlocking {
        val dao = FakeMediaSyncDao()
        val store = MediaSyncStore(dao)
        dao.upsert(
            listOf(
                entity("u1", MediaSyncState.PENDING.name),
                entity("u2", MediaSyncState.SYNCING.name),
                entity("u3", "LEGACY_STATE"),
                entity("u4", MediaSyncState.SYNCED.name),
            )
        )

        val result = store.getResumableUris(limit = 10)

        assertTrue(result.contains("u1"))
        assertTrue(result.contains("u2"))
        assertTrue(result.contains("u3"))
        assertTrue(!result.contains("u4"))
    }

    @Test
    fun getOverview_countsAllStates() = runBlocking {
        val dao = FakeMediaSyncDao()
        val store = MediaSyncStore(dao)
        dao.upsert(
            listOf(
                entity("p", MediaSyncState.PENDING.name),
                entity("s", MediaSyncState.SYNCING.name),
                entity("ok", MediaSyncState.SYNCED.name),
                entity("f", MediaSyncState.FAILED.name),
                entity("k", MediaSyncState.SKIPPED.name),
                entity("u", "UNKNOWN"),
            )
        )

        val overview = store.getOverview()

        assertEquals(1, overview.pending)
        assertEquals(1, overview.syncing)
        assertEquals(1, overview.synced)
        assertEquals(1, overview.failed)
        assertEquals(1, overview.skipped)
        assertEquals(1, overview.unknown)
    }

    private fun candidate(uri: String): MediaCandidate {
        return MediaCandidate(
            uri = uri,
            filename = "img.jpg",
            mimeType = "image/jpeg",
            capturedAtIso = "2026-04-12T10:00:00Z",
            sizeBytes = 1234L,
            mediaId = 1L,
            dateAddedEpochMs = 1L,
        )
    }

    private fun entity(uri: String, state: String): MediaSyncItemEntity {
        return MediaSyncItemEntity(
            uri = uri,
            state = state,
            filename = "file",
            mimeType = "image/jpeg",
            capturedAtIso = "",
            sizeBytes = 1,
            lastError = null,
            updatedAtMs = 0L,
        )
    }
}

private class FakeMediaSyncDao : MediaSyncDao {
    private val rows = linkedMapOf<String, MediaSyncItemEntity>()

    override suspend fun upsert(items: List<MediaSyncItemEntity>) {
        items.forEach { rows[it.uri] = it }
    }

    override suspend fun getByUris(uris: List<String>): List<MediaSyncItemEntity> {
        return uris.mapNotNull { rows[it] }
    }

    override suspend fun getUrisByState(state: String, limit: Int): List<String> {
        return rows.values.asSequence()
            .filter { it.state == state }
            .map { it.uri }
            .take(limit)
            .toList()
    }

    override suspend fun getUrisByStates(states: List<String>, limit: Int): List<String> {
        val allowed = states.toSet()
        return rows.values.asSequence()
            .filter { allowed.contains(it.state) }
            .map { it.uri }
            .take(limit)
            .toList()
    }

    override suspend fun getUrisWithUnknownState(limit: Int): List<String> {
        val known = setOf(
            MediaSyncState.PENDING.name,
            MediaSyncState.SYNCING.name,
            MediaSyncState.SYNCED.name,
            MediaSyncState.FAILED.name,
            MediaSyncState.SKIPPED.name,
        )
        return rows.values.asSequence()
            .filter { !known.contains(it.state) }
            .map { it.uri }
            .take(limit)
            .toList()
    }

    override suspend fun countByState(state: String): Int {
        return rows.values.count { it.state == state }
    }

    override suspend fun countUnknownState(): Int {
        val known = setOf(
            MediaSyncState.PENDING.name,
            MediaSyncState.SYNCING.name,
            MediaSyncState.SYNCED.name,
            MediaSyncState.FAILED.name,
            MediaSyncState.SKIPPED.name,
        )
        return rows.values.count { !known.contains(it.state) }
    }

    override suspend fun updateState(uri: String, state: String, lastError: String?, updatedAtMs: Long): Int {
        val current = rows[uri] ?: return 0
        rows[uri] = current.copy(state = state, lastError = lastError, updatedAtMs = updatedAtMs)
        return 1
    }

    override suspend fun deleteByUri(uri: String) {
        rows.remove(uri)
    }

    override suspend fun clearAll() {
        rows.clear()
    }
}
