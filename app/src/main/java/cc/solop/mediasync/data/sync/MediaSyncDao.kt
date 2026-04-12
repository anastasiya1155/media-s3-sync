package cc.solop.mediasync.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<MediaSyncItemEntity>)

    @Query("SELECT * FROM media_sync_items WHERE uri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<MediaSyncItemEntity>

    @Query("SELECT uri FROM media_sync_items WHERE state = :state LIMIT :limit")
    suspend fun getUrisByState(state: String, limit: Int): List<String>

    @Query("SELECT uri FROM media_sync_items WHERE state IN (:states) LIMIT :limit")
    suspend fun getUrisByStates(states: List<String>, limit: Int): List<String>

    @Query("SELECT uri FROM media_sync_items WHERE state NOT IN ('PENDING', 'SYNCING', 'SYNCED', 'FAILED', 'SKIPPED') LIMIT :limit")
    suspend fun getUrisWithUnknownState(limit: Int): List<String>

    @Query("UPDATE media_sync_items SET state = :state, lastError = :lastError, updatedAtMs = :updatedAtMs WHERE uri = :uri")
    suspend fun updateState(uri: String, state: String, lastError: String?, updatedAtMs: Long): Int

    @Query("DELETE FROM media_sync_items WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM media_sync_items")
    suspend fun clearAll()
}
