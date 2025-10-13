package com.kotopogoda.uploader.core.network.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

data class AttachDeviceRequest(
    val token: String,
)

@JsonClass(generateAdapter = true)
data class AttachDeviceResponseDto(
    @Json(name = "device_id") val deviceId: String? = null,
    @Json(name = "id") val legacyDeviceId: String? = null,
    @Json(name = "device_secret") val deviceSecret: String? = null,
    @Json(name = "secret") val legacyDeviceSecret: String? = null,
)

interface PairingApi {
    @POST("/v1/devices/attach")
    suspend fun attach(@Body request: AttachDeviceRequest): AttachDeviceResponseDto
}

data class AttachDeviceResponse(
    val deviceId: String,
    val hmacKey: String,
)

fun AttachDeviceResponseDto.toDomain(): AttachDeviceResponse {
    val resolvedDeviceId = deviceId ?: legacyDeviceId
    val resolvedSecret = deviceSecret ?: legacyDeviceSecret
    require(!resolvedDeviceId.isNullOrBlank()) { "Response is missing device id" }
    require(!resolvedSecret.isNullOrBlank()) { "Response is missing device secret" }
    return AttachDeviceResponse(deviceId = resolvedDeviceId, hmacKey = resolvedSecret)
}
