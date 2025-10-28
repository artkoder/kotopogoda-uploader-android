package com.kotopogoda.uploader.core.network.security

import com.kotopogoda.uploader.core.logging.HttpFileLogger
import com.kotopogoda.uploader.core.network.logging.HttpLoggingController
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.Base64
import okio.Buffer
import timber.log.Timber

@Singleton
class HmacInterceptor @Inject constructor(
    private val deviceCredsStore: DeviceCredsStore,
    private val httpFileLogger: HttpFileLogger,
    private val httpLoggingController: HttpLoggingController,
    private val clock: Clock = Clock.systemUTC(),
    private val nonceProvider: () -> String = Companion::generateNonce,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        if (shouldBypass(originalRequest)) {
            return chain.proceed(originalRequest)
        }

        val creds = runBlocking { deviceCredsStore.get() }
            ?: throw DeviceNotPairedException()

        val timestamp = clock.instant().epochSecond.toString()
        val nonce = nonceProvider()
        val existingContentSha = originalRequest.header(HEADER_CONTENT_SHA)?.takeIf { it.isNotBlank() }
        val contentSha = existingContentSha ?: run {
            val bodyBytes = originalRequest.body?.let { captureBody(it) } ?: EMPTY_BYTE_ARRAY
            sha256Hex(bodyBytes)
        }
        val idempotencyKey = originalRequest.header(HEADER_IDEMPOTENCY_KEY)?.takeIf { it.isNotBlank() }
        val canonical = buildCanonicalString(
            request = originalRequest,
            deviceId = creds.deviceId,
            timestamp = timestamp,
            nonce = nonce,
            contentSha = contentSha,
            idempotencyKey = idempotencyKey,
        )
        val signature = sign(creds.hmacKey, canonical)

        logSignature(
            timestamp = timestamp,
            nonce = nonce,
            idempotencyKey = idempotencyKey,
            contentSha = contentSha,
            path = originalRequest.url.encodedPath,
            canonical = canonical,
            signature = signature,
        )

        val signedRequest = originalRequest.newBuilder()
            .addHeader(HEADER_DEVICE_ID, creds.deviceId)
            .addHeader(HEADER_TIMESTAMP, timestamp)
            .addHeader(HEADER_NONCE, nonce)
            .header(HEADER_CONTENT_SHA, contentSha)
            .addHeader(HEADER_SIGNATURE, signature)
            .build()

        return chain.proceed(signedRequest)
    }

    private fun logSignature(
        timestamp: String,
        nonce: String,
        idempotencyKey: String?,
        contentSha: String,
        path: String,
        canonical: String,
        signature: String,
    ) {
        if (!httpLoggingController.isEnabled()) {
            return
        }
        val canonicalBase64 = Base64.getEncoder().encodeToString(canonical.toByteArray(StandardCharsets.UTF_8))
        val message = buildString {
            append("UPLOAD/HMAC ")
            append("ts=")
            append(timestamp)
            append(' ')
            append("nonceLen=")
            append(nonce.length)
            append(' ')
            append("idemLen=")
            append(idempotencyKey?.length ?: 0)
            append(' ')
            append("bodySha=")
            append(contentSha)
            append(' ')
            append("path=")
            append(path)
            append(' ')
            append("canonical=")
            append(canonicalBase64)
            append(' ')
            append("sig=")
            append(signature)
        }
        httpFileLogger.log(message)
        Timber.tag("HTTP").i(message)
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
        deviceId: String,
        timestamp: String,
        nonce: String,
        contentSha: String,
        idempotencyKey: String?,
    ): String {
        val method = request.method.uppercase(Locale.US)
        val path = normalizePath(request.url.encodedPath)
        val canonicalQuery = buildCanonicalQuery(request.url) ?: "-"
        val canonicalIdempotencyKey = idempotencyKey ?: "-"
        return listOf(
            method,
            path,
            canonicalQuery,
            timestamp,
            nonce,
            deviceId,
            contentSha,
            canonicalIdempotencyKey,
        ).joinToString(separator = LINE_SEPARATOR)
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
        val keySpec = SecretKeySpec(decodeSecret(secret), "HmacSHA256")
        mac.init(keySpec)
        val signature = mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8))
        return signature.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun normalizePath(path: String): String {
        if (path.isEmpty() || path == "/") return "/"
        var trimmed = path
        while (trimmed.length > 1 && trimmed.endsWith('/')) {
            trimmed = trimmed.dropLast(1)
        }
        return trimmed
    }

    private fun buildCanonicalQuery(url: HttpUrl): String? {
        val encodedQuery = url.encodedQuery ?: return null
        if (encodedQuery.isBlank()) return null
        val parts = encodedQuery.split('&')
            .filter { it.isNotEmpty() }
            .map { part ->
                val separatorIndex = part.indexOf('=')
                if (separatorIndex == -1) {
                    part to null
                } else {
                    part.substring(0, separatorIndex) to part.substring(separatorIndex + 1)
                }
            }
        if (parts.isEmpty()) return null
        return parts
            .sortedWith(compareBy({ it.first }, { it.second ?: "" }))
            .joinToString(separator = "&") { (name, value) ->
                if (value == null) name else "$name=$value"
            }
    }

    private fun decodeSecret(secret: String): ByteArray {
        val trimmed = secret.trim()
        return when {
            trimmed.startsWith(PREFIX_HEX, ignoreCase = true) -> hexToBytes(trimmed.substring(PREFIX_HEX.length))
            trimmed.startsWith(PREFIX_BASE64, ignoreCase = true) -> Base64.getDecoder().decode(trimmed.substring(PREFIX_BASE64.length))
            else -> trimmed.toByteArray(StandardCharsets.UTF_8)
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        val cleaned = value.trim()
        require(cleaned.length % 2 == 0) { "Hex value must have even length" }
        val byteArray = ByteArray(cleaned.length / 2)
        for (index in cleaned.indices step 2) {
            val byte = cleaned.substring(index, index + 2).toInt(16)
            byteArray[index / 2] = byte.toByte()
        }
        return byteArray
    }

    private companion object {
        private val EXCLUDED_PATHS = listOf(
            "/v1/health",
            "/v1/devices/attach",
        )
        private const val LINE_SEPARATOR = "\n"
        private const val HEADER_DEVICE_ID = "X-Device-Id"
        private const val HEADER_TIMESTAMP = "X-Timestamp"
        private const val HEADER_NONCE = "X-Nonce"
        private const val HEADER_CONTENT_SHA = "X-Content-SHA256"
        private const val HEADER_SIGNATURE = "X-Signature"
        private const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
        private val EMPTY_BYTE_ARRAY = ByteArray(0)
        private const val PREFIX_HEX = "hex:"
        private const val PREFIX_BASE64 = "base64:"
        private val secureRandom = SecureRandom()

        private fun generateNonce(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}

class DeviceNotPairedException : IOException("Device is not paired")
