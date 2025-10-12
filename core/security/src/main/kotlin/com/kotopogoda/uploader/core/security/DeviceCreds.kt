package com.kotopogoda.uploader.core.security

data class DeviceCreds(
    val deviceId: String,
    val hmacKey: String,
)
