package com.kotopogoda.uploader.core.network.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET

interface HealthApi {
    @GET("/v1/health")
    suspend fun health(): HealthResponse
}

@JsonClass(generateAdapter = true)
data class HealthResponse(
    @Json(name = "ok") val ok: Boolean?,
    val status: Any? = null,
    val message: String? = null,
)
