package com.kotopogoda.uploader.core.network.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

@JsonClass(generateAdapter = true)
data class UploadAcceptedDto(
    @Json(name = "upload_id") val uploadId: String?,
    @Json(name = "status") val status: String? = null,
    @Json(name = "ocr_remaining_percent") val ocrRemainingPercent: Int? = null,
)

@JsonClass(generateAdapter = true)
data class UploadStatusDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "processed") val processed: Boolean? = null,
    @Json(name = "error") val error: String? = null,
    @Json(name = "ocr_remaining_percent") val ocrRemainingPercent: Int? = null,
)

@JsonClass(generateAdapter = true)
data class UploadLookupDto(
    @Json(name = "upload_id") val uploadId: String?,
    @Json(name = "status") val status: String? = null,
)

interface UploadApi {
    @POST("/v1/uploads")
    suspend fun upload(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Content-SHA256") contentSha256Header: String,
        @Header("X-Has-GPS") hasGpsHeader: String?,
        @Header("X-EXIF-Source") exifSourceHeader: String?,
        @Body body: RequestBody,
    ): Response<UploadAcceptedDto>

    @GET("/v1/uploads/{id}/status")
    suspend fun getStatus(
        @Path("id") uploadId: String,
    ): Response<UploadStatusDto>

    @GET("/v1/uploads/by-key/{idempotencyKey}")
    suspend fun getByIdempotencyKey(
        @Path("idempotencyKey") idempotencyKey: String,
    ): Response<UploadLookupDto>
}
