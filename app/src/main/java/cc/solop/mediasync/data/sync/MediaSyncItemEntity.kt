package cc.solop.mediasync.data.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_sync_items")
data class MediaSyncItemEntity(
    @PrimaryKey val uri: String,
    val state: String,
    val filename: String,
    val mimeType: String,
    val capturedAtIso: String,
    val sizeBytes: Long,
    val lastError: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

