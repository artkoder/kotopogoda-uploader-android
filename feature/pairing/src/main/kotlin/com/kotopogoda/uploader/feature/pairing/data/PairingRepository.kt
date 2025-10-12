package com.kotopogoda.uploader.feature.pairing.data

import com.kotopogoda.uploader.core.network.api.AttachDeviceRequest
import com.kotopogoda.uploader.core.network.api.AttachDeviceResponse
import com.kotopogoda.uploader.core.network.api.PairingApi
import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import javax.inject.Inject
import javax.inject.Singleton

interface PairingRepository {
    suspend fun attach(token: String): AttachDeviceResponse
}

@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val networkClientProvider: NetworkClientProvider,
) : PairingRepository {
    override suspend fun attach(token: String): AttachDeviceResponse {
        val api = networkClientProvider.create(PairingApi::class.java)
        return api.attach(AttachDeviceRequest(token = token))
    }
}
