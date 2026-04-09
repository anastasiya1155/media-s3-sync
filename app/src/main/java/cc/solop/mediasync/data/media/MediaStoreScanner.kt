package cc.solop.mediasync.data.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MediaStoreScanner(private val context: Context) {

    fun scanNewMedia(sinceEpochMsExclusive: Long, limit: Int = Int.MAX_VALUE): List<MediaCandidate> {
        val imageItems = queryCollection(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Images.Media._ID,
            displayNameColumn = MediaStore.Images.Media.DISPLAY_NAME,
            mimeTypeColumn = MediaStore.Images.Media.MIME_TYPE,
            sizeColumn = MediaStore.Images.Media.SIZE,
            dateAddedColumn = MediaStore.Images.Media.DATE_ADDED,
            dateTakenColumn = MediaStore.Images.Media.DATE_TAKEN,
            sinceEpochMsExclusive = sinceEpochMsExclusive,
        )
        val videoItems = queryCollection(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            idColumn = MediaStore.Video.Media._ID,
            displayNameColumn = MediaStore.Video.Media.DISPLAY_NAME,
            mimeTypeColumn = MediaStore.Video.Media.MIME_TYPE,
            sizeColumn = MediaStore.Video.Media.SIZE,
            dateAddedColumn = MediaStore.Video.Media.DATE_ADDED,
            dateTakenColumn = MediaStore.Video.Media.DATE_TAKEN,
            sinceEpochMsExclusive = sinceEpochMsExclusive,
        )
        return (imageItems + videoItems)
            .sortedBy { it.dateAddedEpochMs }
            .take(limit)
    }

    private fun queryCollection(
        collection: android.net.Uri,
        idColumn: String,
        displayNameColumn: String,
        mimeTypeColumn: String,
        sizeColumn: String,
        dateAddedColumn: String,
        dateTakenColumn: String,
        sinceEpochMsExclusive: Long,
    ): List<MediaCandidate> {
        val projection = arrayOf(
            idColumn,
            displayNameColumn,
            mimeTypeColumn,
            sizeColumn,
            dateAddedColumn,
            dateTakenColumn,
        )
        val results = mutableListOf<MediaCandidate>()

        val selection = "$dateAddedColumn > ?"
        val selectionArgs = arrayOf((sinceEpochMsExclusive / 1000L).toString())
        val sortOrder = "$dateAddedColumn ASC"

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idIx = cursor.getColumnIndexOrThrow(idColumn)
            val nameIx = cursor.getColumnIndexOrThrow(displayNameColumn)
            val mimeIx = cursor.getColumnIndexOrThrow(mimeTypeColumn)
            val sizeIx = cursor.getColumnIndexOrThrow(sizeColumn)
            val addedIx = cursor.getColumnIndexOrThrow(dateAddedColumn)
            val takenIx = cursor.getColumnIndexOrThrow(dateTakenColumn)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIx)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                val filename = cursor.getString(nameIx) ?: "media_$id"
                val mimeType = cursor.getString(mimeIx) ?: "application/octet-stream"
                val sizeBytes = cursor.getLong(sizeIx)
                val dateTakenMs = cursor.getLong(takenIx)
                val dateAddedMs = cursor.getLong(addedIx) * 1000L
                val capturedAtMs = if (dateTakenMs > 0) dateTakenMs else dateAddedMs

                results += MediaCandidate(
                    uri = uri,
                    filename = filename,
                    mimeType = mimeType,
                    capturedAtIso = ISO_INSTANT.format(Instant.ofEpochMilli(capturedAtMs)),
                    sizeBytes = sizeBytes,
                    dateAddedEpochMs = dateAddedMs,
                )
            }
        }
        return results
    }

    private companion object {
        val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    }
}
