package com.kotopogoda.uploader.core.network.api

import retrofit2.http.GET

interface HealthApi {
    @GET("/v1/health")
    suspend fun health(): HealthResponse
}

data class HealthResponse(
    val status: String,
    val message: String? = null,
)
