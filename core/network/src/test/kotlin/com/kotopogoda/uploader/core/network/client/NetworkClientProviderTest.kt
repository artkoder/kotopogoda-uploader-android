package com.kotopogoda.uploader.core.network.client

import com.kotopogoda.uploader.core.network.api.AttachDeviceRequest
import com.kotopogoda.uploader.core.network.api.PairingApi
import com.kotopogoda.uploader.core.network.api.toDomain
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import retrofit2.converter.moshi.MoshiConverterFactory

class NetworkClientProviderTest {

    @Test
    fun `create pairing api uses kotlin moshi adapter`() {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"device_id":"device-123","device_secret":"secret"}""")
            )
            server.start()

            val provider = NetworkClientProvider(
                okHttpClient = OkHttpClient.Builder().build(),
                converterFactory = MoshiConverterFactory.create(moshi),
                defaultBaseUrl = server.url("/").toString(),
            )

            val pairingApi = provider.create(PairingApi::class.java)

            val response = runBlocking {
                pairingApi.attach(AttachDeviceRequest(token = "token")).toDomain()
            }

            assertEquals("device-123", response.deviceId)
            assertEquals("secret", response.hmacKey)
        }
    }
}
