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
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"device_id":"device-123","device_secret":"secret-456"}""")
            )
            server.start()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val provider = NetworkClientProvider(
                okHttpClient = OkHttpClient.Builder().build(),
                converterFactory = MoshiConverterFactory.create(moshi),
                defaultBaseUrl = server.url("/").toString(),
            )
            val repository = PairingRepositoryImpl(provider, moshi)

            val response = runBlocking {
                repository.attach("token")
            }

            assertEquals("device-123", response.deviceId)
            assertEquals("secret-456", response.hmacKey)
        }
    }

    @Test
    fun attach_parsesDeprecatedResponse() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"id":"legacy-123","secret":"legacy-456"}""")
            )
            server.start()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val provider = NetworkClientProvider(
                okHttpClient = OkHttpClient.Builder().build(),
                converterFactory = MoshiConverterFactory.create(moshi),
                defaultBaseUrl = server.url("/").toString(),
            )
            val repository = PairingRepositoryImpl(provider, moshi)

            val response = runBlocking {
                repository.attach("token")
            }

            assertEquals("legacy-123", response.deviceId)
            assertEquals("legacy-456", response.hmacKey)
        }
    }

    @Test(expected = PairingException::class)
    fun attach_throwsPairingExceptionOnHttp400() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"token expired"}""")
            )
            server.start()

            val moshi = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
            val provider = NetworkClientProvider(
                okHttpClient = OkHttpClient.Builder().build(),
                converterFactory = MoshiConverterFactory.create(moshi),
                defaultBaseUrl = server.url("/").toString(),
            )
            val repository = PairingRepositoryImpl(provider, moshi)

            runBlocking {
                try {
                    repository.attach("token")
                } catch (error: PairingException) {
                    assertEquals("token expired", error.message)
                    throw error
                }
            }
        }
    }
}
