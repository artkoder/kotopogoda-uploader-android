package com.kotopogoda.uploader.core.network.upload

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import java.io.IOException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.HashingSink
import okio.buffer
import okio.blackholeSink
import timber.log.Timber

internal data class UploadRequestPayload(
    val fileSize: Long,
    val fileSha256Hex: String,
    val requestSha256Hex: String,
    val exifMetadata: ExifMetadata,
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
    hasAccessMediaLocationPermission: Boolean = false,
): UploadRequestPayload = withContext(Dispatchers.IO) {
    val exifMetadata = parseExifMetadata(
        resolver = resolver,
        uri = uri,
        displayName = displayName,
        hasAccessMediaLocationPermission = hasAccessMediaLocationPermission,
    )
    val digest = try {
        MessageDigest.getInstance("SHA-256")
    } catch (error: NoSuchAlgorithmException) {
        throw IOException("SHA-256 algorithm is not available", error)
    }
    var actualSize = 0L
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (read > 0) {
                digest.update(buffer, 0, read)
                actualSize += read
            }
        }
    } ?: throw IOException("Unable to open input stream for $uri")

    val fileSha = digest.digest().toHexString()
    val resolvedSize = if (totalBytes > 0) totalBytes else actualSize
    val boundary = buildBoundary(boundarySeed, fileSha)

    val createBody: (onProgress: ((Long, Long) -> Unit)?) -> MultipartBody = { progressCallback ->
        val fileRequestBody = ContentUriRequestBody(
            resolver = resolver,
            uri = uri,
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
        exifMetadata = exifMetadata,
        createRequestBody = createBody,
    )
}

internal data class ExifMetadata(
    val permissionGranted: Boolean,
    val requireOriginalAttempted: Boolean,
    val requireOriginalSucceeded: Boolean,
    val hasCoordinates: Boolean,
    val latitudeRef: String?,
    val longitudeRef: String?,
    val parseError: Throwable?,
    val requireOriginalError: Throwable?,
) {
    val hasGpsHeaderValue: String
        get() = when {
            parseError != null -> HEADER_VALUE_UNKNOWN
            hasCoordinates -> HEADER_VALUE_TRUE
            requireOriginalSucceeded -> HEADER_VALUE_FALSE
            !requireOriginalAttempted -> HEADER_VALUE_UNKNOWN
            else -> HEADER_VALUE_UNKNOWN
        }

    val exifSourceHeaderValue: String
        get() = when {
            parseError != null -> HEADER_VALUE_UNKNOWN
            requireOriginalSucceeded -> HEADER_VALUE_ORIGINAL
            else -> HEADER_VALUE_REDACTED
        }
}

internal fun ExifMetadata.toLogDetails(): Array<Pair<String, Any?>> = buildList {
    add("has_permission" to permissionGranted)
    add("require_original_attempted" to requireOriginalAttempted)
    add("require_original_succeeded" to requireOriginalSucceeded)
    add("has_coordinates" to hasCoordinates)
    add("has_gps_header" to hasGpsHeaderValue)
    add("exif_source" to exifSourceHeaderValue)
    latitudeRef?.let { add("latitude_ref" to it) }
    longitudeRef?.let { add("longitude_ref" to it) }
    parseError?.let { add("parse_error" to (it::class.simpleName ?: it.javaClass.simpleName)) }
    requireOriginalError?.let { add("require_original_error" to (it::class.simpleName ?: it.javaClass.simpleName)) }
}.toTypedArray()

private fun parseExifMetadata(
    resolver: ContentResolver,
    uri: Uri,
    displayName: String,
    hasAccessMediaLocationPermission: Boolean,
): ExifMetadata {
    var parseError: Throwable? = null
    var requireOriginalError: Throwable? = null
    var requireOriginalAttempted = false
    var requireOriginalSucceeded = false
    var hasCoordinates = false
    var latitudeRef: String? = null
    var longitudeRef: String? = null

    val exif = try {
        resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream)
        } ?: throw IOException("Unable to open input stream for $uri")
    } catch (error: Throwable) {
        parseError = error
        Timber.tag(LOG_TAG).w(error, "Failed to parse EXIF for %s (%s)", displayName, uri)
        null
    }

    if (exif != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasAccessMediaLocationPermission) {
            requireOriginalAttempted = true
            try {
                exif.setRequireOriginal(true)
                requireOriginalSucceeded = true
            } catch (security: SecurityException) {
                requireOriginalError = security
                Timber.tag(LOG_TAG).w(security, "setRequireOriginal denied for %s (%s)", displayName, uri)
            } catch (io: IOException) {
                requireOriginalError = io
                Timber.tag(LOG_TAG).w(io, "setRequireOriginal failed for %s (%s)", displayName, uri)
            } catch (unsupported: RuntimeException) {
                requireOriginalError = unsupported
                Timber.tag(LOG_TAG).w(unsupported, "setRequireOriginal runtime failure for %s (%s)", displayName, uri)
            }
        }

        val latLong = FloatArray(2)
        hasCoordinates = runCatching { exif.getLatLong(latLong) }.getOrDefault(false)
        latitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
        longitudeRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
    }

    val metadata = ExifMetadata(
        permissionGranted = hasAccessMediaLocationPermission,
        requireOriginalAttempted = requireOriginalAttempted,
        requireOriginalSucceeded = requireOriginalSucceeded,
        hasCoordinates = hasCoordinates,
        latitudeRef = latitudeRef,
        longitudeRef = longitudeRef,
        parseError = parseError,
        requireOriginalError = requireOriginalError,
    )

    Timber.tag(LOG_TAG).i(
        "EXIF summary for %s (%s): permission=%s, attempted=%s, success=%s, coords=%s, lat_ref=%s, lon_ref=%s, has_gps_header=%s, exif_source=%s",
        displayName,
        uri,
        metadata.permissionGranted,
        metadata.requireOriginalAttempted,
        metadata.requireOriginalSucceeded,
        metadata.hasCoordinates,
        metadata.latitudeRef ?: "-",
        metadata.longitudeRef ?: "-",
        metadata.hasGpsHeaderValue,
        metadata.exifSourceHeaderValue,
    )

    return metadata
}

private fun buildBoundary(seed: String, fileSha: String): String {
    val sanitizedSeed = seed.filter { it.isLetterOrDigit() }.take(24)
    val normalizedSeed = if (sanitizedSeed.isNotEmpty()) sanitizedSeed else "seed"
    val suffix = fileSha.take(32)
    return "uploader-$normalizedSeed-$suffix"
}

private class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val mediaType: MediaType,
    private val contentLength: Long,
) : RequestBody() {

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: okio.BufferedSink) {
        val input = resolver.openInputStream(uri)
            ?: throw IOException("Unable to open input stream for $uri")
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
private const val HEADER_VALUE_TRUE = "true"
private const val HEADER_VALUE_FALSE = "false"
private const val HEADER_VALUE_UNKNOWN = "unknown"
private const val HEADER_VALUE_ORIGINAL = "original"
private const val HEADER_VALUE_REDACTED = "redacted"
private const val LOG_TAG = "UploadPayload"
