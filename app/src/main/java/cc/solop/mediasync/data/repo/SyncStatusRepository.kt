package cc.solop.mediasync.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "media_sync_prefs")

@Serializable
data class FailedUploadItem(
    val uri: String,
    val reason: String,
    val timestampEpochMs: Long,
)

data class SyncStatus(
    val queuedCount: Long = 0,
    val uploadedCount: Long = 0,
    val duplicateCount: Long = 0,
    val failedCount: Long = 0,
    val failedItems: List<FailedUploadItem> = emptyList(),
    val lastScanEpochMs: Long = 0,
)

class SyncStatusRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val keyQueued = longPreferencesKey("queued_count")
    private val keyUploaded = longPreferencesKey("uploaded_count")
    private val keyDuplicate = longPreferencesKey("duplicate_count")
    private val keyFailed = longPreferencesKey("failed_count")
    private val keyFailedItems = stringPreferencesKey("failed_items_json")
    private val keyLastScanEpochMs = longPreferencesKey("last_scan_epoch_ms")

    val statusFlow: Flow<SyncStatus> = context.dataStore.data.map { prefs ->
        val failedItems = prefs[keyFailedItems]
            ?.let { runCatching { json.decodeFromString<List<FailedUploadItem>>(it) }.getOrNull() }
            ?: emptyList()

        SyncStatus(
            queuedCount = prefs[keyQueued] ?: 0,
            uploadedCount = prefs[keyUploaded] ?: 0,
            duplicateCount = prefs[keyDuplicate] ?: 0,
            failedCount = prefs[keyFailed] ?: 0,
            failedItems = failedItems,
            lastScanEpochMs = prefs[keyLastScanEpochMs] ?: 0,
        )
    }

    suspend fun incrementQueued(by: Long) {
        context.dataStore.edit { prefs ->
            prefs[keyQueued] = (prefs[keyQueued] ?: 0) + by
        }
    }

    suspend fun incrementUploaded() {
        context.dataStore.edit { prefs ->
            prefs[keyUploaded] = (prefs[keyUploaded] ?: 0) + 1
        }
    }

    suspend fun incrementDuplicate() {
        context.dataStore.edit { prefs ->
            prefs[keyDuplicate] = (prefs[keyDuplicate] ?: 0) + 1
        }
    }

    suspend fun incrementFailed(uri: String, reason: String) {
        context.dataStore.edit { prefs ->
            prefs[keyFailed] = (prefs[keyFailed] ?: 0) + 1
            val existing = prefs[keyFailedItems]
                ?.let { runCatching { json.decodeFromString<List<FailedUploadItem>>(it) }.getOrNull() }
                ?: emptyList()
            val updated = (listOf(FailedUploadItem(uri, reason, System.currentTimeMillis())) + existing)
                .take(MAX_FAILED_ITEMS)
            prefs[keyFailedItems] = json.encodeToString(updated)
        }
    }

    suspend fun setLastScanEpochMs(epochMs: Long) {
        context.dataStore.edit { prefs ->
            prefs[keyLastScanEpochMs] = epochMs
        }
    }

    suspend fun getLastScanEpochMs(): Long {
        return statusFlow.map { it.lastScanEpochMs }.first()
    }

    private companion object {
        const val MAX_FAILED_ITEMS = 200
    }
}
