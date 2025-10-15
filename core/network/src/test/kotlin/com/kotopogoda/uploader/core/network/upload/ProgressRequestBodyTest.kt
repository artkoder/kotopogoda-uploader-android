package com.kotopogoda.uploader.core.network.upload

import kotlin.test.assertEquals
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import org.junit.Test

class ProgressRequestBodyTest {

    @Test
    fun emitsProgressWhileWriting() {
        val segments = listOf(
            byteArrayOf(1, 2),
            byteArrayOf(3, 4),
            byteArrayOf(5, 6),
        )
        val requestBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun contentLength(): Long = segments.sumOf { it.size }.toLong()

            override fun writeTo(sink: BufferedSink) {
                segments.forEach { bytes ->
                    sink.write(bytes)
                }
            }
        }
        val emitted = mutableListOf<Pair<Long, Long>>()
        val progressBody = ProgressRequestBody(requestBody, { bytesSent, contentLength ->
            emitted += bytesSent to contentLength
        }, throttleMs = 0)

        val buffer = Buffer()
        progressBody.writeTo(buffer)

        val expectedLength = requestBody.contentLength()
        val expected = listOf(2L, 4L, 6L).map { it to expectedLength }
        assertEquals(expected, emitted)
    }
}
