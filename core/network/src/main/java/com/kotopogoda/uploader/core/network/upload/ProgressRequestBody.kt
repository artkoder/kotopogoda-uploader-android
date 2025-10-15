package com.kotopogoda.uploader.core.network.upload

import android.os.SystemClock
import java.io.IOException
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

internal class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesSent: Long, contentLength: Long) -> Unit,
    private val throttleMs: Long = DEFAULT_THROTTLE_MS,
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val contentLength = runCatching { delegate.contentLength() }.getOrDefault(-1L)
        var bytesWritten = 0L
        var lastEmittedAt = 0L
        var lastEmittedBytes = -1L

        val forwardingSink = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                if (byteCount <= 0) return
                bytesWritten += byteCount
                val now = SystemClock.elapsedRealtime()
                val shouldEmit = bytesWritten == contentLength || now - lastEmittedAt >= throttleMs
                if (shouldEmit && bytesWritten != lastEmittedBytes) {
                    lastEmittedAt = now
                    lastEmittedBytes = bytesWritten
                    onProgress(bytesWritten, contentLength)
                }
            }
        }

        val bufferedSink = forwardingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()

        if (bytesWritten != lastEmittedBytes) {
            onProgress(bytesWritten, contentLength)
        }
    }

    companion object {
        private const val DEFAULT_THROTTLE_MS = 150L
    }
}
