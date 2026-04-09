package cc.solop.mediasync.data.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class MediaStoreScanner(private val context: Context) {

    fun scanNewMedia(
        sinceDateAddedSec: Long,
        sinceMediaIdExclusive: Long,
        limit: Int = Int.MAX_VALUE,
    ): List<MediaCandidate> {
        val effectiveSinceSec = maxOf(sinceDateAddedSec, MIN_SYNC_DATE_ADDED_SEC)
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_TAKEN,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )

        val selection = """
            (${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?)
            AND (
              ${MediaStore.Files.FileColumns.DATE_ADDED} > ?
              OR (
                ${MediaStore.Files.FileColumns.DATE_ADDED} = ?
                AND ${MediaStore.Files.FileColumns._ID} > ?
              )
            )
        """.trimIndent()

        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            effectiveSinceSec.toString(),
            effectiveSinceSec.toString(),
            sinceMediaIdExclusive.toString(),
        )

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} ASC, ${MediaStore.Files.FileColumns._ID} ASC"

        val results = mutableListOf<MediaCandidate>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idIx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameIx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeIx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeIx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val addedIx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val takenIx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)

            while (cursor.moveToNext() && results.size < limit) {
                val id = cursor.getLong(idIx)
                val uri = ContentUris.withAppendedId(collection, id).toString()
                val filename = cursor.getString(nameIx) ?: "media_$id"
                val mimeType = cursor.getString(mimeIx) ?: "application/octet-stream"
                val sizeBytes = cursor.getLong(sizeIx)
                val dateAddedSec = cursor.getLong(addedIx)
                val dateTakenMs = cursor.getLong(takenIx)
                val dateAddedMs = dateAddedSec * 1000L
                val capturedAtMs = if (dateTakenMs > 0) dateTakenMs else dateAddedMs

                results += MediaCandidate(
                    uri = uri,
                    filename = filename,
                    mimeType = mimeType,
                    capturedAtIso = ISO_INSTANT.format(Instant.ofEpochMilli(capturedAtMs)),
                    sizeBytes = sizeBytes,
                    mediaId = id,
                    dateAddedEpochMs = dateAddedMs,
                )
            }
        }

        return results
    }

    private companion object {
        val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
        val MIN_SYNC_DATE_ADDED_SEC: Long = LocalDate.parse("2026-04-04")
            .atStartOfDay(ZoneOffset.UTC)
            .toEpochSecond()
    }
}
