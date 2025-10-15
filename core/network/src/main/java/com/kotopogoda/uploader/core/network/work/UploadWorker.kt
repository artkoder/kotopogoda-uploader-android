package com.kotopogoda.uploader.core.network.work

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.upload.ProgressRequestBody
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import com.kotopogoda.uploader.core.network.upload.UploadForegroundKind
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.network.upload.UploadWorkErrorKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploadApi: UploadApi,
    private val foregroundDelegate: UploadForegroundDelegate,
    private val summaryStarter: UploadSummaryStarter,
) : CoroutineWorker(appContext, params) {

    private var lastProgressSnapshot = ProgressSnapshot()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        summaryStarter.ensureRunning()
        val uriString = inputData.getString(UploadEnqueuer.KEY_URI)
            ?: return@withContext Result.failure()
        val idempotencyKey = inputData.getString(UploadEnqueuer.KEY_IDEMPOTENCY_KEY)
            ?: return@withContext Result.failure()
        val displayName = inputData.getString(UploadEnqueuer.KEY_DISPLAY_NAME) ?: DEFAULT_FILE_NAME
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext Result.failure()

        try {
            val totalBytes = appContext.contentResolver
                .openAssetFileDescriptor(uri, "r")
                ?.use { it.length }
                ?: -1L

            updateProgress(
                displayName = displayName,
                progress = INDETERMINATE_PROGRESS,
                bytesSent = if (totalBytes > 0) 0L else null,
                totalBytes = totalBytes.takeIf { it > 0 }
            )

            val payload = readDocumentPayload(uri, totalBytes, displayName)
            val mimeType = appContext.contentResolver.getType(uri)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_MIME_TYPE
            val mediaType = mimeType.toMediaTypeOrNull() ?: DEFAULT_MIME_TYPE.toMediaType()

            var lastReportedPercent = -1
            var lastBytesSent: Long? = null
            val fileRequestBody = ProgressRequestBody(
                payload.bytes.toRequestBody(mediaType),
                onProgress = { bytesSent: Long, _: Long ->
                val percent = if (payload.size > 0) {
                    ((bytesSent * 100) / payload.size).toInt().coerceIn(0, 100)
                } else {
                    100
                }
                if (percent != lastReportedPercent) {
                    lastReportedPercent = percent
                    runBlocking {
                        lastBytesSent = bytesSent
                        updateProgress(
                            displayName = displayName,
                            progress = percent,
                            bytesSent = bytesSent,
                            totalBytes = payload.size.takeIf { it > 0 }
                        )
                    }
                }
                }
            )
            val filePart = MultipartBody.Part.createFormData(
                "file",
                displayName,
                fileRequestBody
            )
            val response = uploadApi.upload(
                idempotencyKey = idempotencyKey,
                file = filePart,
                contentSha256Part = payload.sha256Hex.toPlainRequestBody(),
                mime = mimeType.toPlainRequestBody(),
                size = payload.size.toString().toPlainRequestBody(),
            )

            when (response.code()) {
                202, 409 -> {
                    val uploadId = response.body()?.uploadId
                    if (uploadId.isNullOrBlank()) {
                        recordError(displayName, UploadWorkErrorKind.UNEXPECTED)
                        Result.retry()
                    } else {
                        updateProgress(
                            displayName = displayName,
                            progress = 100,
                            bytesSent = lastBytesSent ?: payload.size,
                            totalBytes = payload.size.takeIf { it > 0 }
                        )
                        Result.success(
                            buildResultData(
                                displayName = displayName,
                                uriString = uriString,
                                uploadId = uploadId,
                                bytesSent = lastProgressSnapshot.bytesSent,
                                totalBytes = lastProgressSnapshot.totalBytes
                            )
                        )
                    }
                }
                413, 415 -> failureResult(
                    displayName = displayName,
                    uriString = uriString,
                    errorKind = UploadWorkErrorKind.HTTP,
                    httpCode = response.code()
                )
                429 -> retryResult(
                    displayName = displayName,
                    errorKind = UploadWorkErrorKind.HTTP,
                    httpCode = response.code()
                )
                in 500..599 -> retryResult(
                    displayName = displayName,
                    errorKind = UploadWorkErrorKind.HTTP,
                    httpCode = response.code()
                )
                else -> failureResult(
                    displayName = displayName,
                    uriString = uriString,
                    errorKind = UploadWorkErrorKind.HTTP,
                    httpCode = response.code()
                )
            }
        } catch (io: IOException) {
            retryResult(displayName, UploadWorkErrorKind.IO)
        } catch (error: Exception) {
            failureResult(displayName, uriString, UploadWorkErrorKind.UNEXPECTED)
        }
    }

    private suspend fun readDocumentPayload(
        uri: Uri,
        totalBytes: Long,
        displayName: String
    ): FilePayload {
        val resolver = appContext.contentResolver
        val inputStream = resolver.openInputStream(uri)
            ?: throw IOException("Unable to open input stream for $uri")
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val output = ByteArrayOutputStream(if (totalBytes in 1..Int.MAX_VALUE) totalBytes.toInt() else DEFAULT_BUFFER_CAPACITY)
        inputStream.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) {
                    break
                }
                if (read > 0) {
                    total += read
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    if (totalBytes > 0) {
                        val progress = ((total * 100) / totalBytes).toInt().coerceIn(0, 99)
                        updateProgress(
                            displayName = displayName,
                            progress = progress,
                            bytesSent = total,
                            totalBytes = totalBytes
                        )
                    }
                }
            }
        }
        if (totalBytes <= 0) {
            updateProgress(displayName, INDETERMINATE_PROGRESS)
        } else {
            updateProgress(
                displayName = displayName,
                progress = 99,
                bytesSent = total,
                totalBytes = totalBytes
            )
        }
        val sha256Hex = digest.digest().toHexString()
        return FilePayload(
            bytes = output.toByteArray(),
            size = total,
            sha256Hex = sha256Hex,
        )
    }

    private suspend fun updateProgress(
        displayName: String,
        progress: Int,
        bytesSent: Long? = lastProgressSnapshot.bytesSent,
        totalBytes: Long? = lastProgressSnapshot.totalBytes,
        errorKind: UploadWorkErrorKind? = null,
        httpCode: Int? = null,
    ) {
        lastProgressSnapshot = ProgressSnapshot(
            progress = progress,
            bytesSent = bytesSent,
            totalBytes = totalBytes
        )
        val builder = Data.Builder()
            .putInt(UploadEnqueuer.KEY_PROGRESS, progress)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
        bytesSent?.let { builder.putLong(UploadEnqueuer.KEY_BYTES_SENT, it) }
        totalBytes?.let { builder.putLong(UploadEnqueuer.KEY_TOTAL_BYTES, it) }
        errorKind?.let { builder.putString(UploadEnqueuer.KEY_ERROR_KIND, it.rawValue) }
        httpCode?.let { builder.putInt(UploadEnqueuer.KEY_HTTP_CODE, it) }
        setProgress(builder.build())
        setForeground(createForeground(displayName, progress))
    }

    private suspend fun recordError(
        displayName: String,
        errorKind: UploadWorkErrorKind,
        httpCode: Int? = null
    ) {
        val snapshot = lastProgressSnapshot
        updateProgress(
            displayName = displayName,
            progress = snapshot.progress,
            bytesSent = snapshot.bytesSent,
            totalBytes = snapshot.totalBytes,
            errorKind = errorKind,
            httpCode = httpCode
        )
    }

    private suspend fun retryResult(
        displayName: String,
        errorKind: UploadWorkErrorKind,
        httpCode: Int? = null
    ): Result {
        recordError(displayName, errorKind, httpCode)
        return Result.retry()
    }

    private suspend fun failureResult(
        displayName: String,
        uriString: String,
        errorKind: UploadWorkErrorKind,
        httpCode: Int? = null
    ): Result {
        recordError(displayName, errorKind, httpCode)
        return Result.failure(
            buildResultData(
                displayName = displayName,
                uriString = uriString,
                bytesSent = lastProgressSnapshot.bytesSent,
                totalBytes = lastProgressSnapshot.totalBytes,
                errorKind = errorKind,
                httpCode = httpCode
            )
        )
    }

    private fun buildResultData(
        displayName: String,
        uriString: String,
        uploadId: String? = null,
        bytesSent: Long? = null,
        totalBytes: Long? = null,
        errorKind: UploadWorkErrorKind? = null,
        httpCode: Int? = null,
    ): Data {
        val builder = Data.Builder()
            .putString(UploadEnqueuer.KEY_URI, uriString)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
        uploadId?.let { builder.putString(UploadEnqueuer.KEY_UPLOAD_ID, it) }
        bytesSent?.let { builder.putLong(UploadEnqueuer.KEY_BYTES_SENT, it) }
        totalBytes?.let { builder.putLong(UploadEnqueuer.KEY_TOTAL_BYTES, it) }
        errorKind?.let { builder.putString(UploadEnqueuer.KEY_ERROR_KIND, it.rawValue) }
        httpCode?.let { builder.putInt(UploadEnqueuer.KEY_HTTP_CODE, it) }
        return builder.build()
    }

    private fun createForeground(displayName: String, progress: Int): ForegroundInfo {
        return foregroundDelegate.create(displayName, progress, id, UploadForegroundKind.UPLOAD)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_BUFFER_CAPACITY = 0
        private const val DEFAULT_FILE_NAME = "photo.jpg"
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private const val INDETERMINATE_PROGRESS = -1
    }

    private data class FilePayload(
        val bytes: ByteArray,
        val size: Long,
        val sha256Hex: String,
    )

    private data class ProgressSnapshot(
        val progress: Int = INDETERMINATE_PROGRESS,
        val bytesSent: Long? = null,
        val totalBytes: Long? = null,
    )

    private fun String.toPlainRequestBody() =
        toRequestBody("text/plain".toMediaType())

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
