package cc.solop.mediasync.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cc.solop.mediasync.data.store.appDataStore
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class FailedUploadItem(
    val uri: String,
    val reason: String,
    val timestampEpochMs: Long,
)

@Serializable
data class UploadedItem(
    val uri: String,
    val key: String,
    val timestampEpochMs: Long,
)

data class SyncStatus(
    val queuedCount: Long = 0,
    val uploadedCount: Long = 0,
    val duplicateCount: Long = 0,
    val failedCount: Long = 0,
    val uploadedItems: List<UploadedItem> = emptyList(),
    val failedItems: List<FailedUploadItem> = emptyList(),
    val lastScanEpochMs: Long = 0,
)

data class ScanCursor(
    val dateAddedSec: Long,
    val mediaIdExclusive: Long,
)

class SyncStatusRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val keyQueued = longPreferencesKey("queued_count")
    private val keyUploaded = longPreferencesKey("uploaded_count")
    private val keyDuplicate = longPreferencesKey("duplicate_count")
    private val keyFailed = longPreferencesKey("failed_count")
    private val keyUploadedItems = stringPreferencesKey("uploaded_items_json")
    private val keyFailedItems = stringPreferencesKey("failed_items_json")
    private val keySyncedUris = stringPreferencesKey("synced_uris_json")
    private val keyLastScanEpochMs = longPreferencesKey("last_scan_epoch_ms")
    private val keyLastScanDateAddedSec = longPreferencesKey("last_scan_date_added_sec")
    private val keyLastScanMediaId = longPreferencesKey("last_scan_media_id")

    val statusFlow: Flow<SyncStatus> = context.appDataStore.data.map { prefs ->
        val uploadedItems = prefs[keyUploadedItems]
            ?.let { runCatching { json.decodeFromString<List<UploadedItem>>(it) }.getOrNull() }
            ?: emptyList()
        val failedItems = prefs[keyFailedItems]
            ?.let { runCatching { json.decodeFromString<List<FailedUploadItem>>(it) }.getOrNull() }
            ?: emptyList()

        SyncStatus(
            queuedCount = prefs[keyQueued] ?: 0,
            uploadedCount = prefs[keyUploaded] ?: 0,
            duplicateCount = prefs[keyDuplicate] ?: 0,
            failedCount = prefs[keyFailed] ?: 0,
            uploadedItems = uploadedItems,
            failedItems = failedItems,
            lastScanEpochMs = prefs[keyLastScanEpochMs] ?: 0,
        )
    }

    suspend fun incrementQueued(by: Long) {
        context.appDataStore.edit { prefs ->
            prefs[keyQueued] = (prefs[keyQueued] ?: 0) + by
        }
    }

    suspend fun incrementUploaded(uri: String, key: String) {
        context.appDataStore.edit { prefs ->
            prefs[keyQueued] = ((prefs[keyQueued] ?: 0) - 1).coerceAtLeast(0)
            prefs[keyUploaded] = (prefs[keyUploaded] ?: 0) + 1
            val synced = prefs[keySyncedUris]
                ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
                ?: emptyList()
            prefs[keySyncedUris] = json.encodeToString(
                ListSerializer(String.serializer()),
                (listOf(uri) + synced).distinct().take(MAX_SYNCED_URIS),
            )
            val existing = prefs[keyUploadedItems]
                ?.let { runCatching { json.decodeFromString<List<UploadedItem>>(it) }.getOrNull() }
                ?: emptyList()
            val updated = (listOf(UploadedItem(uri, key, System.currentTimeMillis())) + existing)
                .take(MAX_UPLOADED_ITEMS)
            prefs[keyUploadedItems] = json.encodeToString(
                ListSerializer(UploadedItem.serializer()),
                updated
            )
        }
    }

    suspend fun incrementDuplicate(uri: String? = null) {
        context.appDataStore.edit { prefs ->
            prefs[keyQueued] = ((prefs[keyQueued] ?: 0) - 1).coerceAtLeast(0)
            prefs[keyDuplicate] = (prefs[keyDuplicate] ?: 0) + 1
            if (!uri.isNullOrBlank()) {
                val synced = prefs[keySyncedUris]
                    ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
                    ?: emptyList()
                prefs[keySyncedUris] = json.encodeToString(
                    ListSerializer(String.serializer()),
                    (listOf(uri) + synced).distinct().take(MAX_SYNCED_URIS),
                )
            }
        }
    }

    suspend fun incrementFailed(uri: String, reason: String) {
        context.appDataStore.edit { prefs ->
            prefs[keyQueued] = ((prefs[keyQueued] ?: 0) - 1).coerceAtLeast(0)
            prefs[keyFailed] = (prefs[keyFailed] ?: 0) + 1
            val existing = prefs[keyFailedItems]
                ?.let { runCatching { json.decodeFromString<List<FailedUploadItem>>(it) }.getOrNull() }
                ?: emptyList()
            val updated = (listOf(FailedUploadItem(uri, reason, System.currentTimeMillis())) + existing)
                .take(MAX_FAILED_ITEMS)
            prefs[keyFailedItems] = json.encodeToString(
                ListSerializer(FailedUploadItem.serializer()),
                updated
            )
        }
    }

    suspend fun setLastScanEpochMs(epochMs: Long) {
        context.appDataStore.edit { prefs ->
            prefs[keyLastScanEpochMs] = epochMs
        }
    }

    suspend fun getScanCursor(): ScanCursor {
        val prefs = context.appDataStore.data.first()
        return ScanCursor(
            dateAddedSec = prefs[keyLastScanDateAddedSec] ?: 0L,
            mediaIdExclusive = prefs[keyLastScanMediaId] ?: 0L,
        )
    }

    suspend fun setScanCursor(dateAddedSec: Long, mediaIdExclusive: Long) {
        context.appDataStore.edit { prefs ->
            prefs[keyLastScanDateAddedSec] = dateAddedSec
            prefs[keyLastScanMediaId] = mediaIdExclusive
            prefs[keyLastScanEpochMs] = dateAddedSec * 1000L
        }
    }

    suspend fun resetScanWatermark() {
        context.appDataStore.edit { prefs ->
            prefs[keyLastScanEpochMs] = 0L
            prefs[keyLastScanDateAddedSec] = 0L
            prefs[keyLastScanMediaId] = 0L
        }
    }

    suspend fun skipBacklogFromNow() {
        val nowSec = Instant.now().epochSecond
        context.appDataStore.edit { prefs ->
            prefs[keyLastScanEpochMs] = nowSec * 1000L
            prefs[keyLastScanDateAddedSec] = nowSec
            // Ensure we don't include any already-existing item in the same second.
            prefs[keyLastScanMediaId] = Long.MAX_VALUE
            prefs[keyQueued] = 0L
        }
    }

    suspend fun clearQueuedCount() {
        context.appDataStore.edit { prefs ->
            prefs[keyQueued] = 0L
        }
    }

    suspend fun resetAllStateForFreshStart() {
        context.appDataStore.edit { prefs ->
            prefs[keyQueued] = 0L
            prefs[keyUploaded] = 0L
            prefs[keyDuplicate] = 0L
            prefs[keyFailed] = 0L
            prefs.remove(keyUploadedItems)
            prefs.remove(keyFailedItems)
            prefs.remove(keySyncedUris)
            prefs[keyLastScanEpochMs] = 0L
            prefs[keyLastScanDateAddedSec] = 0L
            prefs[keyLastScanMediaId] = 0L
        }
    }

    suspend fun getLastScanEpochMs(): Long {
        return statusFlow.map { it.lastScanEpochMs }.first()
    }

    suspend fun getSyncedUrisSet(): Set<String> {
        val prefs = context.appDataStore.data.first()
        val synced = prefs[keySyncedUris]
            ?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            ?: emptyList()
        return synced.toSet()
    }

    suspend fun getFailedUrisSet(): Set<String> {
        val prefs = context.appDataStore.data.first()
        val failed = prefs[keyFailedItems]
            ?.let { runCatching { json.decodeFromString<List<FailedUploadItem>>(it) }.getOrNull() }
            ?: emptyList()
        return failed.map { it.uri }.toSet()
    }

    private companion object {
        const val MAX_FAILED_ITEMS = 200
        const val MAX_UPLOADED_ITEMS = 200
        const val MAX_SYNCED_URIS = 10_000
    }
}
