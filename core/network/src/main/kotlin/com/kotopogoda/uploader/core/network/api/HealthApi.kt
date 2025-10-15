package com.kotopogoda.uploader.core.network.api

import com.squareup.moshi.JsonClass
import retrofit2.http.GET

interface HealthApi {
    @GET("/v1/health")
    suspend fun health(): HealthResponse
}

@JsonClass(generateAdapter = true)
data class HealthResponse(
    val status: Any?,
    val message: String? = null,
)
