package cc.solop.mediasync.data.sync

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [MediaSyncItemEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MediaSyncDatabase : RoomDatabase() {
    abstract fun mediaSyncDao(): MediaSyncDao
}

