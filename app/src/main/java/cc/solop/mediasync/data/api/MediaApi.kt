package cc.solop.mediasync.data.api

import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class MediaActionRequest(
    val action: String,
    val filename: String? = null,
    val mimeType: String? = null,
    val capturedAt: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val key: String? = null,
    val reason: String? = null,
)

@JsonClass(generateAdapter = true)
data class MediaActionResponse(
    val duplicate: Boolean? = null,
    val uploadUrl: String? = null,
    val key: String? = null,
)

@JsonClass(generateAdapter = true)
data class MediaListItem(
    val key: String,
    val mimeType: String,
    val capturedAt: String,
    val sizeBytes: Long,
    val sha256: String,
)

@JsonClass(generateAdapter = true)
data class MediaListResponse(
    val items: List<MediaListItem> = emptyList(),
    val pageToken: String? = null,
)

interface MediaApi {
    @POST("api/media")
    suspend fun postMediaAction(@Body body: MediaActionRequest): MediaActionResponse

    @GET("api/media")
    suspend fun listMedia(
        @Query("pageSize") pageSize: Int = 100,
        @Query("date") date: String,
        @Query("pageToken") pageToken: String? = null,
    ): MediaListResponse
}
