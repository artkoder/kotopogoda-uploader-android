package com.kotopogoda.uploader.feature.pairing.data

import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.kotopogoda.uploader.feature.pairing.PAIRING_TOKEN_FORMAT_ERROR
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
                repository.attach("abc234")
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
                repository.attach("abc234")
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
                    repository.attach("abc234")
                } catch (error: PairingException) {
                    assertEquals("token expired", error.message)
                    throw error
                }
            }
        }
    }

    @Test
    fun attach_sendsNormalizedToken() {
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

            runBlocking {
                repository.attach("  abc234  ")
            }

            val request = server.takeRequest()
            assertEquals("{\"token\":\"ABC234\"}", request.body.readUtf8())
        }
    }

    @Test
    fun attach_throwsWhenTokenInvalid() {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val provider = NetworkClientProvider(
            okHttpClient = OkHttpClient.Builder().build(),
            converterFactory = MoshiConverterFactory.create(moshi),
            defaultBaseUrl = "https://example.com",
        )
        val repository = PairingRepositoryImpl(provider, moshi)

        try {
            runBlocking {
                repository.attach("abc1")
            }
        } catch (error: PairingException) {
            assertEquals(PAIRING_TOKEN_FORMAT_ERROR, error.message)
            return
        }

        throw AssertionError("Expected PairingException to be thrown")
    }
}
