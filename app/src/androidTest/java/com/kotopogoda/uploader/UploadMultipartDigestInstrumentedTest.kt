package com.kotopogoda.uploader

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.text.Charsets
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import com.kotopogoda.uploader.core.network.upload.prepareUploadRequestPayload

@RunWith(AndroidJUnit4::class)
class UploadMultipartDigestInstrumentedTest {

    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockWebServer = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun requestDigestMatchesHeader() = runBlocking {
        val file = File.createTempFile("instrumented", ".txt", context.cacheDir).apply {
            writeText("instrumented upload body")
            deleteOnExit()
        }
        val uri = Uri.fromFile(file)
        val payload = prepareUploadRequestPayload(
            resolver = context.contentResolver,
            uri = uri,
            displayName = file.name,
            mimeType = "text/plain",
            mediaType = "text/plain".toMediaType(),
            totalBytes = file.length(),
            boundarySeed = "instrumented-key",
        )
        val requestBody = payload.createRequestBody(null)

        mockWebServer.enqueue(MockResponse().setResponseCode(202))

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(mockWebServer.url("/v1/uploads"))
            .post(requestBody)
            .header("Idempotency-Key", "instrumented-key")
            .header("X-Content-SHA256", payload.requestSha256Hex)
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(202, response.code)
        }

        val recorded = mockWebServer.takeRequest()
        val recordedBytes = recorded.body.readByteArray()
        val recordedDigest = recordedBytes.sha256Hex()
        assertEquals(payload.requestSha256Hex, recorded.getHeader("X-Content-SHA256"))
        assertEquals(payload.requestSha256Hex, recordedDigest)

        val boundary = recorded.getHeader("Content-Type")?.substringAfter("boundary=")?.trim()
        val resolvedBoundary = assertNotNull(boundary, "Multipart boundary missing")
        val bodyString = String(recordedBytes, Charsets.UTF_8)
        val contentShaPart = bodyString.findMultipartValue(resolvedBoundary, "content_sha256")
        assertEquals(payload.fileSha256Hex, contentShaPart)
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = digest.digest(this)
        return hashed.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun String.findMultipartValue(boundary: String, name: String): String? {
        val delimiter = "--$boundary"
        return split(delimiter)
            .asSequence()
            .map { it.trim('\r', '\n') }
            .firstNotNullOfOrNull { part ->
                if (part.isEmpty() || part == "--") return@firstNotNullOfOrNull null
                if (!part.contains("name=\"$name\"")) return@firstNotNullOfOrNull null
                val lines = part.split("\r\n")
                val blankIndex = lines.indexOf("")
                if (blankIndex == -1 || blankIndex + 1 >= lines.size) return@firstNotNullOfOrNull null
                lines.drop(blankIndex + 1)
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("--") }
                    ?.trimEnd('\r', '\n')
            }
    }
}
