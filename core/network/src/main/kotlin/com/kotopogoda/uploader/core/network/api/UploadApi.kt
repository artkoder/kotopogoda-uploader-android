package com.kotopogoda.uploader.core.network.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

@JsonClass(generateAdapter = true)
data class UploadAcceptedDto(
    @Json(name = "upload_id") val uploadId: String?,
    @Json(name = "status") val status: String? = null,
)

@JsonClass(generateAdapter = true)
data class UploadStatusDto(
    @Json(name = "status") val status: String? = null,
    @Json(name = "processed") val processed: Boolean? = null,
    @Json(name = "error") val error: String? = null,
)

interface UploadApi {
    @Multipart
    @POST("/v1/uploads")
    suspend fun upload(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Header("X-Content-SHA256") contentSha256Header: String,
        @Part file: MultipartBody.Part,
        @Part("content_sha256") contentSha256Part: RequestBody,
        @Part("mime") mime: RequestBody,
        @Part("size") size: RequestBody,
        @Part("exif_date") exifDate: RequestBody? = null,
        @Part("original_relpath") originalRelpath: RequestBody? = null,
    ): Response<UploadAcceptedDto>

    @GET("/v1/uploads/{id}/status")
    suspend fun getStatus(
        @Path("id") uploadId: String,
    ): Response<UploadStatusDto>
}
