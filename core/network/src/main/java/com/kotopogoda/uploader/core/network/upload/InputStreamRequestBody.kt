package com.kotopogoda.uploader.core.network.upload

import java.io.InputStream
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

internal class InputStreamRequestBody(
    private val mediaType: MediaType?,
    private val inputStream: InputStream,
    private val contentLength: Long,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val onProgress: ((bytesSent: Long, contentLength: Long) -> Unit)? = null,
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = if (contentLength >= 0) contentLength else -1L

    override fun writeTo(sink: BufferedSink) {
        val totalLength = contentLength()
        var bytesWritten = 0L
        val buffer = ByteArray(bufferSize)

        inputStream.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) {
                    break
                }
                if (read <= 0) {
                    continue
                }
                sink.write(buffer, 0, read)
                bytesWritten += read
                onProgress?.invoke(bytesWritten, totalLength)
            }
        }

        if (onProgress != null && bytesWritten == 0L) {
            onProgress.invoke(0L, totalLength)
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }
}
