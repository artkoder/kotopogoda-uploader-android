package com.kotopogoda.uploader.core.network.security

import com.kotopogoda.uploader.core.security.DeviceCredsStore
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

@Singleton
class HmacInterceptor @Inject constructor(
    private val deviceCredsStore: DeviceCredsStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (shouldBypass(originalRequest)) {
            return chain.proceed(originalRequest)
        }

        val creds = runBlocking { deviceCredsStore.get() }
            ?: throw DeviceNotPairedException()

        val timestamp = Instant.now().epochSecond.toString()
        val nonce = UUID.randomUUID().toString()
        val bodyBytes = originalRequest.body?.let { captureBody(it) } ?: EMPTY_BYTE_ARRAY
        val contentSha = sha256Hex(bodyBytes)
        val canonical = buildCanonicalString(originalRequest, timestamp, nonce, contentSha)
        val signature = sign(creds.hmacKey, canonical)

        val signedRequest = originalRequest.newBuilder()
            .addHeader(HEADER_DEVICE_ID, creds.deviceId)
            .addHeader(HEADER_TIMESTAMP, timestamp)
            .addHeader(HEADER_NONCE, nonce)
            .addHeader(HEADER_CONTENT_SHA, contentSha)
            .addHeader(HEADER_SIGNATURE, signature)
            .build()

        return chain.proceed(signedRequest)
    }

    private fun shouldBypass(request: Request): Boolean {
        val path = request.url.encodedPath
        return EXCLUDED_PATHS.any { path.startsWith(it) }
    }

    private fun captureBody(body: okhttp3.RequestBody): ByteArray {
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readByteArray()
    }

    private fun buildCanonicalString(
        request: Request,
        timestamp: String,
        nonce: String,
        contentSha: String,
    ): String {
        return buildString {
            append(request.method)
            append(DELIMITER)
            append(request.url.encodedPath)
            append(DELIMITER)
            append(timestamp)
            append(DELIMITER)
            append(nonce)
            append(DELIMITER)
            append(contentSha)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = digest.digest(bytes)
        return hashed.joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }

    private fun sign(secret: String, canonical: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
        mac.init(keySpec)
        val signature = mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
        return signature.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        private val EXCLUDED_PATHS = listOf(
            "/v1/health",
            "/v1/devices/attach",
        )
        private const val DELIMITER = "|"
        private const val HEADER_DEVICE_ID = "X-Device-Id"
        private const val HEADER_TIMESTAMP = "X-Timestamp"
        private const val HEADER_NONCE = "X-Nonce"
        private const val HEADER_CONTENT_SHA = "X-Content-SHA256"
        private const val HEADER_SIGNATURE = "X-Signature"
        private val EMPTY_BYTE_ARRAY = ByteArray(0)
    }
}

class DeviceNotPairedException : IOException("Device is not paired")
