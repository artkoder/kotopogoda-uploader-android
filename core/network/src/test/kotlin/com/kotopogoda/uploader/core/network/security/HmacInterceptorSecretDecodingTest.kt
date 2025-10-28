package com.kotopogoda.uploader.core.network.security

import com.kotopogoda.uploader.core.security.DeviceCreds
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.assertContentEquals
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Before
import org.junit.Test

class HmacInterceptorSecretDecodingTest {

    private lateinit var interceptor: HmacInterceptor

    @Before
    fun setUp() {
        interceptor = HmacInterceptor(
            deviceCredsStore = object : DeviceCredsStore {
                override suspend fun save(deviceId: String, hmacKey: String) = Unit
                override suspend fun get() = null
                override suspend fun clear() = Unit
                override val credsFlow = emptyFlow<DeviceCreds?>()
            },
            httpFileLogger = mockk(relaxed = true),
            httpLoggingController = mockk(relaxed = true),
        )
    }

    @Test
    fun `plain hex secrets are decoded`() {
        val secret = "0011aaff"
        val decoded = decode(secret)
        assertContentEquals(byteArrayOf(0x00, 0x11, 0xAA.toByte(), 0xFF.toByte()), decoded)
    }

    @Test
    fun `base64 secrets are decoded`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val secret = Base64.getEncoder().encodeToString(bytes)
        val decoded = decode(secret)
        assertContentEquals(bytes, decoded)
    }

    @Test
    fun `text secrets fallback to utf8`() {
        val secret = "plain-text-secret"
        val decoded = decode(secret)
        assertContentEquals(secret.toByteArray(StandardCharsets.UTF_8), decoded)
    }

    @Test
    fun `invalid secrets fallback to utf8`() {
        val secret = "0123Z"
        val decoded = decode(secret)
        assertContentEquals(secret.toByteArray(StandardCharsets.UTF_8), decoded)
    }

    private fun decode(secret: String): ByteArray {
        val method = HmacInterceptor::class.java.getDeclaredMethod("decodeSecret", String::class.java)
        method.isAccessible = true
        return method.invoke(interceptor, secret) as ByteArray
    }
}
