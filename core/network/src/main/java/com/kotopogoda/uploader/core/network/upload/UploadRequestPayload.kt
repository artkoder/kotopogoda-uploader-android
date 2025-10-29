package com.kotopogoda.uploader.core.network.upload

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.HashingSink
import okio.buffer
import okio.blackholeSink
import com.kotopogoda.uploader.core.data.util.logUriReadDebug
import com.kotopogoda.uploader.core.data.util.requireOriginalIfNeeded

internal data class UploadRequestPayload(
    val fileSize: Long,
    val fileSha256Hex: String,
    val requestSha256Hex: String,
    val createRequestBody: (onProgress: ((bytesSent: Long, contentLength: Long) -> Unit)?) -> RequestBody,
)

internal suspend fun prepareUploadRequestPayload(
    resolver: ContentResolver,
    uri: Uri,
    displayName: String,
    mimeType: String,
    mediaType: MediaType,
    totalBytes: Long,
    boundarySeed: String,
): UploadRequestPayload = withContext(Dispatchers.IO) {
    val digest = try {
        MessageDigest.getInstance("SHA-256")
    } catch (error: NoSuchAlgorithmException) {
        throw IOException("SHA-256 algorithm is not available", error)
    }
    val normalizedUri = resolver.requireOriginalIfNeeded(uri)
    resolver.logUriReadDebug("UploadRequestPayload.digest", uri, normalizedUri)
    var actualSize = 0L
    resolver.openInputStream(normalizedUri)?.use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (read > 0) {
                digest.update(buffer, 0, read)
                actualSize += read
            }
        }
    } ?: throw IOException("Unable to open input stream for $normalizedUri")

    val fileSha = digest.digest().toHexString()
    val resolvedSize = if (totalBytes > 0) totalBytes else actualSize
    val boundary = buildBoundary(boundarySeed, fileSha)

    val createBody: (onProgress: ((Long, Long) -> Unit)?) -> MultipartBody = { progressCallback ->
        val fileRequestBody = ContentUriRequestBody(
            resolver = resolver,
            originalUri = uri,
            normalizedUri = normalizedUri,
            mediaType = mediaType,
            contentLength = resolvedSize,
        )
        val streamingBody = if (progressCallback != null) {
            ProgressRequestBody(fileRequestBody, progressCallback)
        } else {
            fileRequestBody
        }
        MultipartBody.Builder(boundary)
            .setType(MultipartBody.FORM)
            .addFormDataPart("content_sha256", fileSha)
            .addFormDataPart("mime", mimeType)
            .addFormDataPart("size", resolvedSize.toString())
            .addFormDataPart("file", displayName, streamingBody)
            .build()
    }

    val hashingBody = createBody(null)
    val hashingSink = HashingSink.sha256(blackholeSink())
    val buffered = hashingSink.buffer()
    hashingBody.writeTo(buffered)
    buffered.close()
    val requestSha = hashingSink.hash.hex()

    UploadRequestPayload(
        fileSize = resolvedSize,
        fileSha256Hex = fileSha,
        requestSha256Hex = requestSha,
        createRequestBody = createBody,
    )
}

private fun buildBoundary(seed: String, fileSha: String): String {
    val sanitizedSeed = seed.filter { it.isLetterOrDigit() }.take(24)
    val normalizedSeed = if (sanitizedSeed.isNotEmpty()) sanitizedSeed else "seed"
    val suffix = fileSha.take(32)
    return "uploader-$normalizedSeed-$suffix"
}

private class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val originalUri: Uri,
    private val normalizedUri: Uri,
    private val mediaType: MediaType,
    private val contentLength: Long,
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: okio.BufferedSink) {
        resolver.logUriReadDebug("UploadRequestPayload.body", originalUri, normalizedUri)
        val input = resolver.openInputStream(normalizedUri)
            ?: throw IOException("Unable to open input stream for $normalizedUri")
        input.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                if (read > 0) {
                    sink.write(buffer, 0, read)
                }
            }
        }
    }
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val BUFFER_SIZE = 64 * 1024
