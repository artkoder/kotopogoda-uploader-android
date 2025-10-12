package com.kotopogoda.uploader.core.security

import kotlinx.coroutines.flow.Flow

interface DeviceCredsStore {
    suspend fun save(deviceId: String, hmacKey: String)
    suspend fun get(): DeviceCreds?
    suspend fun clear()
    val credsFlow: Flow<DeviceCreds?>
}
