package com.kotopogoda.uploader.core.network.upload

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.min
import okio.Buffer
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadTaskRunnerTest {

    @Test
    fun `prepareUploadRequestPayload streams data and computes digests`() {
        val uri = Uri.parse("content://test/document")
        val data = ByteArray(25_000) { (it % 251).toByte() }
        val streamFactory = RecordingStreamFactory(data, chunkSize = 1_024)
        val resolver = mockk<ContentResolver>()
        every { resolver.openAssetFileDescriptor(uri, any()) } returns null
        every { resolver.openInputStream(uri) } answers { streamFactory.nextStream() }
        every { resolver.getType(uri) } returns "image/jpeg"
        every { resolver.persistedUriPermissions } returns emptyList()

        val payload = prepareUploadRequestPayload(
            resolver = resolver,
            uri = uri,
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            mediaType = "image/jpeg".toMediaType(),
            totalBytes = -1L,
            boundarySeed = "digest-test",
        )

        val expectedFileSha = MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        assertEquals(data.size.toLong(), payload.fileSize)
        assertEquals(expectedFileSha, payload.fileSha256Hex)
        assertEquals(UploadGpsState.UNKNOWN, payload.gpsState)
        assertEquals(UploadExifSource.ORIGINAL, payload.exifSource)

        val requestBody = payload.createRequestBody(null)
        val sink = Buffer()
        requestBody.writeTo(sink)
        val bodyBytes = sink.readByteArray()
        val expectedRequestSha = MessageDigest.getInstance("SHA-256")
            .digest(bodyBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        assertEquals(expectedRequestSha, payload.requestSha256Hex)

        assertEquals(3, streamFactory.streams.size)
        streamFactory.streams.forEach { stream ->
            assertTrue(stream.closed)
            assertTrue("Expected multiple reads per stream", stream.totalReads > 1)
            assertEquals(data.size.toLong(), stream.totalBytesRead)
            assertTrue(stream.readSizes.all { it <= stream.chunkSize })
        }
    }

    @Test(expected = IOException::class)
    fun `prepareUploadRequestPayload throws when input stream missing`() {
        val uri = Uri.parse("content://test/missing")
        val resolver = mockk<ContentResolver>()
        every { resolver.openAssetFileDescriptor(uri, any()) } returns null
        every { resolver.openInputStream(uri) } returns null
        every { resolver.persistedUriPermissions } returns emptyList()

        prepareUploadRequestPayload(
            resolver = resolver,
            uri = uri,
            displayName = "missing.jpg",
            mimeType = "image/jpeg",
            mediaType = "image/jpeg".toMediaType(),
            totalBytes = -1L,
            boundarySeed = "missing",
        )
    }

    private class RecordingStreamFactory(
        private val data: ByteArray,
        val chunkSize: Int,
    ) {
        val streams = mutableListOf<RecordingStream>()
        private var provided = 0

        fun nextStream(): InputStream {
            if (provided >= MAX_STREAMS) {
                throw IllegalStateException("Unexpected stream request #$provided")
            }
            val stream = RecordingStream(data, chunkSize)
            streams += stream
            provided += 1
            return stream
        }

        private class RecordingStream(
            private val data: ByteArray,
            val chunkSize: Int,
        ) : InputStream() {
            private var position = 0
            val readSizes = mutableListOf<Int>()
            var totalReads: Int = 0
                private set
            var totalBytesRead: Long = 0
                private set
            var closed: Boolean = false
                private set

            override fun read(): Int {
                val buffer = ByteArray(1)
                val read = read(buffer, 0, 1)
                return if (read == -1) -1 else buffer[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (position >= data.size) {
                    return -1
                }
                val toRead = min(len, min(chunkSize, data.size - position))
                System.arraycopy(data, position, b, off, toRead)
                position += toRead
                readSizes += toRead
                totalReads += 1
                totalBytesRead += toRead
                return toRead
            }

            override fun close() {
                closed = true
            }
        }

        companion object {
            private const val MAX_STREAMS = 3
        }
    }
}
