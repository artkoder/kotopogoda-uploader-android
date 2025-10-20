package com.kotopogoda.uploader.core.logging.diagnostic

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
)
