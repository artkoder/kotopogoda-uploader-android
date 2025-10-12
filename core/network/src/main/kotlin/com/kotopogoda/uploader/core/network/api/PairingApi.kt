package com.kotopogoda.uploader.core.network.api

import retrofit2.http.Body
import retrofit2.http.POST

data class AttachDeviceRequest(
    val token: String,
)

data class AttachDeviceResponse(
    val deviceId: String,
    val hmacKey: String,
)

interface PairingApi {
    @POST("/v1/devices/attach")
    suspend fun attach(@Body request: AttachDeviceRequest): AttachDeviceResponse
}
