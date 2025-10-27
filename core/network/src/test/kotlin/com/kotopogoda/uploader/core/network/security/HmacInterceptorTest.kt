package com.kotopogoda.uploader.core.network.security

import com.kotopogoda.uploader.core.security.DeviceCreds
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    fun `interceptor uses unix timestamp when signing requests`() {
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

            val recordedTimestamp = recorded.getHeader("X-Timestamp") ?: fail("Timestamp header is missing")
            val timestampSeconds = recordedTimestamp.toLongOrNull() ?: fail("Timestamp is not numeric")
            val expectedSeconds = fixedInstant.epochSecond
            assertTrue(
                kotlin.math.abs(timestampSeconds - expectedSeconds) <= 2,
                "Timestamp differs from expected time: $timestampSeconds vs $expectedSeconds",
            )
            val canonicalTimestamp = recordedTimestamp
            assertEquals(nonce, recorded.getHeader("X-Nonce"))
            assertEquals(deviceCreds.deviceId, recorded.getHeader("X-Device-Id"))

            val expectedContentSha = sha256Hex(bodyText.toByteArray(StandardCharsets.UTF_8))
            assertEquals(expectedContentSha, recorded.getHeader("X-Content-SHA256"))

            val expectedCanonical = listOf(
                "POST",
                "/v1/upload",
                "-",
                canonicalTimestamp,
                nonce,
                deviceCreds.deviceId,
                expectedContentSha,
                "-",
            ).joinToString(separator = "\n")

            val expectedSignature = sign(deviceCreds.hmacKey, expectedCanonical)
            assertEquals(expectedSignature, recorded.getHeader("X-Signature"))
        }
    }

    @Test
    fun `interceptor reuses provided content sha without rebuffering body`() {
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
            val providedSha = sha256Hex(payloadBytes)
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

            assertEquals(1, countingBody.writeCount)

            val recorded = server.takeRequest(1, TimeUnit.SECONDS) ?: fail("Request was not recorded")

            val recordedTimestamp = recorded.getHeader("X-Timestamp") ?: fail("Timestamp header is missing")
            val timestampSeconds = recordedTimestamp.toLongOrNull() ?: fail("Timestamp is not numeric")
            val expectedSeconds = fixedInstant.epochSecond
            assertTrue(
                kotlin.math.abs(timestampSeconds - expectedSeconds) <= 2,
                "Timestamp differs from expected time: $timestampSeconds vs $expectedSeconds",
            )
            val canonicalTimestamp = recordedTimestamp
            assertEquals(nonce, recorded.getHeader("X-Nonce"))
            assertEquals(deviceCreds.deviceId, recorded.getHeader("X-Device-Id"))
            assertEquals(providedSha, recorded.getHeader("X-Content-SHA256"))

            val expectedCanonical = listOf(
                "POST",
                "/v1/upload",
                "-",
                canonicalTimestamp,
                nonce,
                deviceCreds.deviceId,
                providedSha,
                "-",
            ).joinToString(separator = "\n")
        
            val expectedSignature = sign(deviceCreds.hmacKey, expectedCanonical)
            assertEquals(expectedSignature, recorded.getHeader("X-Signature"))
        }
    }

    @Test
    fun `canonical query is sorted and included in signature`() {
        val fixedInstant = Instant.parse("2024-05-01T12:34:56Z")
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val nonce = "fixed-nonce"
        val deviceCreds = DeviceCreds(deviceId = "device-456", hmacKey = "secret-key")
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
                .url(server.url("/v1/upload/?b=2&a=1&a=0&z"))
                .post(bodyText.toRequestBody("text/plain".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }

            val recorded = server.takeRequest(1, TimeUnit.SECONDS) ?: fail("Request was not recorded")

            val recordedTimestamp = recorded.getHeader("X-Timestamp") ?: fail("Timestamp header missing")
            val expectedContentSha = sha256Hex(bodyText.toByteArray(StandardCharsets.UTF_8))

            val expectedCanonical = listOf(
                "POST",
                "/v1/upload",
                "a=0&a=1&b=2&z",
                recordedTimestamp,
                nonce,
                deviceCreds.deviceId,
                expectedContentSha,
                "-",
            ).joinToString(separator = "\n")

            val expectedSignature = sign(deviceCreds.hmacKey, expectedCanonical)
            assertEquals(expectedSignature, recorded.getHeader("X-Signature"))
        }
    }

    @Test
    fun `idempotency key participates in canonical string`() {
        val fixedInstant = Instant.parse("2024-05-01T12:34:56Z")
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val nonce = "fixed-nonce"
        val deviceCreds = DeviceCreds(deviceId = "device-456", hmacKey = "secret-key")
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
            val idempotencyKey = "upload:123"
            val request = Request.Builder()
                .url(server.url("/v1/upload"))
                .post(bodyText.toRequestBody("text/plain".toMediaType()))
                .header("Idempotency-Key", idempotencyKey)
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }

            val recorded = server.takeRequest(1, TimeUnit.SECONDS) ?: fail("Request was not recorded")

            val recordedTimestamp = recorded.getHeader("X-Timestamp") ?: fail("Timestamp header missing")
            val expectedContentSha = sha256Hex(bodyText.toByteArray(StandardCharsets.UTF_8))

            val expectedCanonical = listOf(
                "POST",
                "/v1/upload",
                "-",
                recordedTimestamp,
                nonce,
                deviceCreds.deviceId,
                expectedContentSha,
                idempotencyKey,
            ).joinToString(separator = "\n")

            val expectedSignature = sign(deviceCreds.hmacKey, expectedCanonical)
            assertEquals(idempotencyKey, recorded.getHeader("Idempotency-Key"))
            assertEquals(expectedSignature, recorded.getHeader("X-Signature"))
        }
    }

    @Test
    fun `default nonce provider produces crypto-strong hex`() {
        val fixedInstant = Instant.parse("2024-05-01T12:34:56Z")
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val deviceCreds = DeviceCreds(deviceId = "device-456", hmacKey = "secret-key")
        val interceptor = HmacInterceptor(
            deviceCredsStore = FakeDeviceCredsStore(deviceCreds),
            clock = clock,
        )

        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))

            val client = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()

            val request = Request.Builder()
                .url(server.url("/v1/upload"))
                .post("payload".toRequestBody("text/plain".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
            }

            val recorded = server.takeRequest(1, TimeUnit.SECONDS) ?: fail("Request was not recorded")
            val nonce = recorded.getHeader("X-Nonce") ?: fail("Nonce header missing")
            assertTrue(nonce.length >= 32, "Nonce must be at least 32 hex chars")
            assertTrue(nonce.matches(Regex("[0-9a-f]+")), "Nonce must be lower-case hex")
        }
    }

    @Test
    fun `hex encoded secret is decoded before signing`() {
        val fixedInstant = Instant.parse("2024-05-01T12:34:56Z")
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val nonce = "fixed-nonce"
        val secretBytes = ByteArray(32) { index -> index.toByte() }
        val hexSecret = buildString(secretBytes.size * 2) {
            secretBytes.forEach { append("%02x".format(it)) }
        }
        val deviceCreds = DeviceCreds(deviceId = "device-789", hmacKey = "hex:$hexSecret")
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
            val recordedTimestamp = recorded.getHeader("X-Timestamp") ?: fail("Timestamp header missing")
            val expectedContentSha = sha256Hex(bodyText.toByteArray(StandardCharsets.UTF_8))

            val expectedCanonical = listOf(
                "POST",
                "/v1/upload",
                "-",
                recordedTimestamp,
                nonce,
                deviceCreds.deviceId,
                expectedContentSha,
                "-",
            ).joinToString(separator = "\n")

            val expectedSignature = sign(secretBytes, expectedCanonical)
            assertEquals(expectedSignature, recorded.getHeader("X-Signature"))
        }
    }

    @Test
    fun `base64 encoded secret is decoded before signing`() {
        val fixedInstant = Instant.parse("2024-05-01T12:34:56Z")
        val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val nonce = "fixed-nonce"
        val secretBytes = ByteArray(32) { index -> (255 - index).toByte() }
        val base64Secret = Base64.getEncoder().encodeToString(secretBytes)
        val deviceCreds = DeviceCreds(deviceId = "device-789", hmacKey = "base64:$base64Secret")
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
            val recordedTimestamp = recorded.getHeader("X-Timestamp") ?: fail("Timestamp header missing")
            val expectedContentSha = sha256Hex(bodyText.toByteArray(StandardCharsets.UTF_8))

            val expectedCanonical = listOf(
                "POST",
                "/v1/upload",
                "-",
                recordedTimestamp,
                nonce,
                deviceCreds.deviceId,
                expectedContentSha,
                "-",
            ).joinToString(separator = "\n")

            val expectedSignature = sign(secretBytes, expectedCanonical)
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
        return sign(secret.toByteArray(StandardCharsets.UTF_8), canonical)
    }

    private fun sign(secret: ByteArray, canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret, "HmacSHA256")
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
