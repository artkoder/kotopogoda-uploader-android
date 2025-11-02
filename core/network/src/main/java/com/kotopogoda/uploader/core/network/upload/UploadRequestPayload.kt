package com.kotopogoda.uploader.core.network.upload

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.io.buffered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.HashingSink
import okio.buffer
import okio.blackholeSink
import com.kotopogoda.uploader.core.data.util.URI_READ_LOG_TAG
import com.kotopogoda.uploader.core.data.util.hasPersistedReadPermission
import com.kotopogoda.uploader.core.data.util.isMediaUri
import com.kotopogoda.uploader.core.data.util.logUriReadDebug
import com.kotopogoda.uploader.core.data.util.requireOriginalIfNeeded
import timber.log.Timber

internal data class UploadRequestPayload(
    val fileSize: Long,
    val fileSha256Hex: String,
    val requestSha256Hex: String,
    val gpsState: UploadGpsState,
    val exifSource: UploadExifSource,
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
    val normalizedUri = resolver.requireOriginalIfNeeded(uri)
    resolver.logUriReadDebug("UploadRequestPayload.digest", uri, normalizedUri)
    val hasPersistedRead = resolver.hasPersistedReadPermission(uri, normalizedUri)
    val requireOriginalAttempted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isMediaUri(uri)
    val requireOriginalSucceeded = requireOriginalAttempted && normalizedUri != uri

    val inspectionOutcome = resolver.openInputStream(normalizedUri)?.buffered()?.use { stream ->
        val inspection = inspectExif(stream)
        val digestResult = if (inspection.canContinueReading) {
            computeDigest(stream)
        } else {
            null
        }
        InspectionOutcome(inspection, digestResult)
    } ?: throw IOException("Unable to open input stream for $normalizedUri")

    val digestResult = inspectionOutcome.digest
        ?: resolver.openInputStream(normalizedUri)?.buffered()?.use { stream -> computeDigest(stream) }
        ?: throw IOException("Unable to open input stream for $normalizedUri")

    val gpsState = when (inspectionOutcome.inspection.hasCoordinates) {
        true -> UploadGpsState.PRESENT
        false -> UploadGpsState.ABSENT
        null -> UploadGpsState.UNKNOWN
    }
    val exifSource = when {
        requireOriginalSucceeded -> UploadExifSource.ORIGINAL
        !requireOriginalAttempted -> UploadExifSource.ORIGINAL
        else -> UploadExifSource.ANONYMIZED
    }

    Timber.tag(URI_READ_LOG_TAG).d(
        "UploadRequestPayload.exif: persisted=%s requireOriginalAttempted=%s requireOriginalSucceeded=%s gps=%s latRef=%s lonRef=%s",
        hasPersistedRead,
        requireOriginalAttempted,
        requireOriginalSucceeded,
        gpsState.headerValue,
        inspectionOutcome.inspection.latitudeRef ?: "-",
        inspectionOutcome.inspection.longitudeRef ?: "-",
    )

    val fileSha = digestResult.sha256.toHexString()
    val actualSize = digestResult.size
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
        gpsState = gpsState,
        exifSource = exifSource,
        createRequestBody = createBody,
    )
}

private fun inspectExif(stream: java.io.BufferedInputStream): ExifInspection {
    if (!stream.markSupported()) {
        return ExifInspection(hasCoordinates = null, latitudeRef = null, longitudeRef = null, canContinueReading = true)
    }
    stream.mark(EXIF_MARK_LIMIT)
    val exif = runCatching { ExifInterface(stream) }.getOrNull()
    val latLong = exif?.latLong
    val hasLatLong = latLong != null
    val resetSucceeded = runCatching { stream.reset(); true }.getOrElse { false }
    return ExifInspection(
        hasCoordinates = exif?.let { if (hasLatLong) true else false },
        latitudeRef = exif?.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF),
        longitudeRef = exif?.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF),
        canContinueReading = resetSucceeded,
    )
}

private fun computeDigest(stream: java.io.InputStream): DigestComputation {
    val digest = try {
        MessageDigest.getInstance("SHA-256")
    } catch (error: NoSuchAlgorithmException) {
        throw IOException("SHA-256 algorithm is not available", error)
    }
    val buffer = ByteArray(BUFFER_SIZE)
    var actualSize = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read == -1) break
        if (read > 0) {
            digest.update(buffer, 0, read)
            actualSize += read
        }
    }
    return DigestComputation(size = actualSize, sha256 = digest.digest())
}

internal enum class UploadGpsState(val headerValue: String) {
    PRESENT("true"),
    ABSENT("false"),
    UNKNOWN("unknown"),
}

internal enum class UploadExifSource(val headerValue: String) {
    ORIGINAL("original"),
    ANONYMIZED("anonymized"),
}

private data class ExifInspection(
    val hasCoordinates: Boolean?,
    val latitudeRef: String?,
    val longitudeRef: String?,
    val canContinueReading: Boolean,
)

private data class DigestComputation(
    val size: Long,
    val sha256: ByteArray,
)

private data class InspectionOutcome(
    val inspection: ExifInspection,
    val digest: DigestComputation?,
)

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
private const val EXIF_MARK_LIMIT = 256 * 1024
