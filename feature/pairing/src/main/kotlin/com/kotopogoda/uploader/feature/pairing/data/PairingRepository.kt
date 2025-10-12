package com.kotopogoda.uploader.feature.pairing.data

import com.kotopogoda.uploader.core.network.api.AttachDeviceRequest
import com.kotopogoda.uploader.core.network.api.AttachDeviceResponse
import com.kotopogoda.uploader.core.network.api.PairingApi
import javax.inject.Inject
import javax.inject.Singleton

interface PairingRepository {
    suspend fun attach(token: String): AttachDeviceResponse
}

@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val api: PairingApi,
) : PairingRepository {
    override suspend fun attach(token: String): AttachDeviceResponse {
        return api.attach(AttachDeviceRequest(token = token))
    }
}
