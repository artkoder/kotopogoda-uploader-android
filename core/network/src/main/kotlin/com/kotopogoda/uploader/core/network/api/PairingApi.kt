package com.kotopogoda.uploader.core.network.api

import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.POST

data class AttachDeviceRequest(
    val token: String,
)

data class AttachDeviceResponse(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "device_secret") val hmacKey: String,
)

interface PairingApi {
    @POST("/v1/devices/attach")
    suspend fun attach(@Body request: AttachDeviceRequest): AttachDeviceResponse
}
