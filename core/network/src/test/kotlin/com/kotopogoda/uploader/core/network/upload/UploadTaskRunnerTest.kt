package com.kotopogoda.uploader.core.network.upload

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter

class UploadTaskRunnerTest {

    @Test
    fun `readDocumentPayload streams data in blocks`() {
        val uri = Uri.parse("content://test/document")
        val data = ByteArray(25_000) { (it % 251).toByte() }
        val digestStream = RecordingInputStream(data, chunkSize = 1_024)
        val requestStream = RecordingInputStream(data, chunkSize = 1_024)
        val resolver = mockk<ContentResolver>()
        every { resolver.openAssetFileDescriptor(uri, any()) } returns null
        every { resolver.openInputStream(uri) } returnsMany listOf(digestStream, requestStream)
        every { resolver.getType(uri) } returns "image/jpeg"

        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver

        val uploadApi = mockk<UploadApi>(relaxed = true)
        val summaryStarter = mockk<UploadSummaryStarter>(relaxed = true)

        val runner = UploadTaskRunner(context, uploadApi, summaryStarter)
        val payload = invokeReadDocumentPayload(runner, uri)

        val expectedSha = MessageDigest.getInstance("SHA-256").digest(data).joinToString(separator = "") {
            byte -> "%02x".format(byte)
        }

        assertEquals(data.size.toLong(), payload.size)
        assertEquals(expectedSha, payload.sha256Hex)

        val bufferSize = resolveBufferSize(runner)
        assertTrue(digestStream.readSizes.isNotEmpty())
        assertTrue(digestStream.readSizes.all { it == bufferSize })
        assertTrue(digestStream.totalReads > 1)

        val body = payload.requestBody
        assertEquals(data.size.toLong(), body.contentLength())

        val sink = Buffer()
        body.writeTo(sink)

        assertTrue(requestStream.closed)
        assertTrue(requestStream.totalReads > 1)
        assertEquals(digestStream.totalBytesRead, requestStream.totalBytesRead)
        assertTrue(requestStream.readSizes.all { it == bufferSize })

        assertEquals(data.toList(), sink.readByteArray().toList())
    }

    @Test(expected = IOException::class)
    fun `readDocumentPayload throws when input stream missing`() {
        val uri = Uri.parse("content://test/missing")
        val resolver = mockk<ContentResolver>()
        every { resolver.openAssetFileDescriptor(uri, any()) } returns null
        every { resolver.openInputStream(uri) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver

        val uploadApi = mockk<UploadApi>(relaxed = true)
        val summaryStarter = mockk<UploadSummaryStarter>(relaxed = true)

        val runner = UploadTaskRunner(context, uploadApi, summaryStarter)

        invokeReadDocumentPayload(runner, uri)
    }

    private fun invokeReadDocumentPayload(runner: UploadTaskRunner, uri: Uri): UploadTaskRunner.FilePayload {
        val mediaType = runner.javaClass.getDeclaredMethod("resolveMediaType", Uri::class.java)
            .apply { isAccessible = true }
            .invoke(runner, uri) as okhttp3.MediaType
        val method = runner.javaClass.getDeclaredMethod(
            "readDocumentPayload",
            Uri::class.java,
            okhttp3.MediaType::class.java
        )
        method.isAccessible = true
        return method.invoke(runner, uri, mediaType) as UploadTaskRunner.FilePayload
    }

    private fun resolveBufferSize(runner: UploadTaskRunner): Int {
        val companionField = UploadTaskRunner::class.java.getDeclaredField("Companion")
        companionField.isAccessible = true
        val companion = companionField.get(null)
        val bufferField = companion.javaClass.getDeclaredField("BUFFER_SIZE")
        bufferField.isAccessible = true
        return bufferField.getInt(companion)
    }

    private class RecordingInputStream(
        private val data: ByteArray,
        private val chunkSize: Int,
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
            readSizes += len
            if (position >= data.size) {
                return -1
            }
            val toRead = minOf(len, chunkSize, data.size - position)
            System.arraycopy(data, position, b, off, toRead)
            position += toRead
            totalReads += 1
            totalBytesRead += toRead
            return toRead
        }

        override fun close() {
            closed = true
        }
    }
}
