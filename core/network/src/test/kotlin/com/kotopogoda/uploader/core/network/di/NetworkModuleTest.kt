package com.kotopogoda.uploader.core.network.di

import com.kotopogoda.uploader.api.infrastructure.ApiClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.kotopogoda.uploader.core.network.security.HmacInterceptor
import com.kotopogoda.uploader.core.security.DeviceCreds
import com.kotopogoda.uploader.core.security.DeviceCredsStore

class NetworkModuleTest {

    @Test
    fun `provideApiClient uses prod base url`() {
        val okHttpClient = OkHttpClient.Builder().build()
        val prodBaseUrl = "https://cat-weather-new.fly.dev"

        val apiClient = NetworkModule.provideApiClient(okHttpClient, prodBaseUrl)

        val baseUrlField = ApiClient::class.java.getDeclaredField("baseUrl").apply {
            isAccessible = true
        }
        val actualBaseUrl = baseUrlField.get(apiClient) as String

        assertEquals(prodBaseUrl.trimEnd('/') + "/", actualBaseUrl)
    }

    @Test
    fun `provideOkHttpClient adds nonce header with hex format`() {
        val nonceProvider = NetworkModule.provideNonceProvider()
        val deviceCreds = DeviceCreds(deviceId = "device-123", hmacKey = "secret-key")
        val clock = Clock.fixed(Instant.parse("2024-05-01T12:34:56Z"), ZoneOffset.UTC)
        val hmacInterceptor = HmacInterceptor(
            deviceCredsStore = FakeDeviceCredsStore(deviceCreds),
            clock = clock,
            nonceProvider = nonceProvider,
        )
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }
        val okHttpClient = NetworkModule.provideOkHttpClient(loggingInterceptor, hmacInterceptor)

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))

            val request = Request.Builder()
                .url(server.url("/v1/upload"))
                .post("payload".toRequestBody("text/plain".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }

            val recorded = server.takeRequest(1, TimeUnit.SECONDS) ?: fail("Request was not recorded")
            val nonce = requireNotNull(recorded.getHeader("X-Nonce")) { "Nonce header missing" }
            assertTrue(nonce.length >= 32, "Nonce must be at least 32 hex chars")
            assertTrue(nonce.matches(Regex("[0-9a-f]+")), "Nonce must be lower-case hex")
        }
    }

    private class FakeDeviceCredsStore(
        private val creds: DeviceCreds?,
    ) : DeviceCredsStore {
        override suspend fun save(deviceId: String, hmacKey: String) = Unit

        override suspend fun get(): DeviceCreds? = creds

        override suspend fun clear() = Unit

        override val credsFlow: Flow<DeviceCreds?> = MutableStateFlow(creds)
    }
}
