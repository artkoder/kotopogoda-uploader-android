package com.kotopogoda.uploader.feature.pairing.data

import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import retrofit2.converter.moshi.MoshiConverterFactory

class PairingRepositoryImplTest {

    @Test
    fun attach_parsesSnakeCaseResponse() {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"device_id":"device-123","device_secret":"secret-456"}""")
            )
            server.start()

            val provider = NetworkClientProvider(
                okHttpClient = OkHttpClient.Builder().build(),
                converterFactory = MoshiConverterFactory.create(moshi),
                defaultBaseUrl = server.url("/").toString(),
            )
            val repository = PairingRepositoryImpl(provider)

            val response = runBlocking {
                repository.attach("token")
            }

            assertEquals("device-123", response.deviceId)
            assertEquals("secret-456", response.hmacKey)
        }
    }
}
