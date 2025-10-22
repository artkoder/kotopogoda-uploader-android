package com.kotopogoda.uploader.core.network.security

import com.kotopogoda.uploader.core.security.DeviceCreds
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSink
import org.junit.Test

class HmacInterceptorTest {

    @Test
    fun `interceptor uses RFC3339 timestamp when signing requests`() {
        val fixedInstant = Instant.parse("2024-03-23T10:15:30.123Z")
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val nonce = "fixed-nonce"
        val deviceCreds = DeviceCreds(deviceId = "device-123", hmacKey = "secret-key")
        val interceptor = HmacInterceptor(
            deviceCredsStore = FakeDeviceCredsStore(deviceCreds),
            clock = clock,
            nonceProvider = { nonce },
        )

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))

            val client = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()

            val bodyText = "payload"
            val request = Request.Builder()
                .url(server.url("/v1/upload"))
                .post(bodyText.toRequestBody("text/plain".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }

            val recorded = server.takeRequest(1, TimeUnit.SECONDS) ?: fail("Request was not recorded")

            val expectedTimestamp = "2024-03-23T10:15:30Z"
            assertEquals(expectedTimestamp, recorded.getHeader("X-Timestamp"))
            assertEquals(nonce, recorded.getHeader("X-Nonce"))
            assertEquals(deviceCreds.deviceId, recorded.getHeader("X-Device-Id"))

            val expectedContentSha = sha256Hex(bodyText.toByteArray(StandardCharsets.UTF_8))
            assertEquals(expectedContentSha, recorded.getHeader("X-Content-SHA256"))

            val expectedCanonical = listOf(
                "POST",
                "/v1/upload",
                deviceCreds.deviceId,
                expectedTimestamp,
                nonce,
                expectedContentSha,
            ).joinToString(separator = "\n")

            val expectedSignature = sign(deviceCreds.hmacKey, expectedCanonical)
            assertEquals(expectedSignature, recorded.getHeader("X-Signature"))
        }
    }

    @Test
    fun `interceptor recomputes content sha even when header is present`() {
        val fixedInstant = Instant.parse("2024-03-23T10:15:30.123Z")
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val nonce = "fixed-nonce"
        val deviceCreds = DeviceCreds(deviceId = "device-123", hmacKey = "secret-key")
        val interceptor = HmacInterceptor(
            deviceCredsStore = FakeDeviceCredsStore(deviceCreds),
            clock = clock,
            nonceProvider = { nonce },
        )

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))

            val client = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()

            val payloadBytes = ByteArray(128) { index -> (index % 251).toByte() }
            val providedSha = "deadbeef"
            val countingBody = CountingRequestBody(
                data = payloadBytes,
                mediaType = "application/octet-stream".toMediaType(),
            )

            val request = Request.Builder()
                .url(server.url("/v1/upload"))
                .post(countingBody)
                .header("X-Content-SHA256", providedSha)
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }

            assertEquals(2, countingBody.writeCount)

            val recorded = server.takeRequest(1, TimeUnit.SECONDS) ?: fail("Request was not recorded")

            val expectedTimestamp = "2024-03-23T10:15:30Z"
            assertEquals(expectedTimestamp, recorded.getHeader("X-Timestamp"))
            assertEquals(nonce, recorded.getHeader("X-Nonce"))
            assertEquals(deviceCreds.deviceId, recorded.getHeader("X-Device-Id"))
            val expectedContentSha = sha256Hex(payloadBytes)
            assertEquals(expectedContentSha, recorded.getHeader("X-Content-SHA256"))

            val expectedCanonical = listOf(
                "POST",
                "/v1/upload",
                deviceCreds.deviceId,
                expectedTimestamp,
                nonce,
                expectedContentSha,
            ).joinToString(separator = "\n")

            val expectedSignature = sign(deviceCreds.hmacKey, expectedCanonical)
            assertEquals(expectedSignature, recorded.getHeader("X-Signature"))
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

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun sign(secret: String, canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class CountingRequestBody(
        private val data: ByteArray,
        private val mediaType: MediaType,
    ) : RequestBody() {
        var writeCount: Int = 0
            private set

        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = data.size.toLong()

        override fun writeTo(sink: BufferedSink) {
            writeCount += 1
            sink.write(data)
        }
    }
}
