package com.kotopogoda.uploader.core.network.work

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.ForegroundInfo
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import com.kotopogoda.uploader.core.network.upload.UploadForegroundKind
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
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

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        summaryStarter.ensureRunning()
        val uriString = inputData.getString(UploadEnqueuer.KEY_URI)
            ?: return@withContext Result.failure()
        val idempotencyKey = inputData.getString(UploadEnqueuer.KEY_IDEMPOTENCY_KEY)
            ?: return@withContext Result.failure()
        val displayName = inputData.getString(UploadEnqueuer.KEY_DISPLAY_NAME) ?: DEFAULT_FILE_NAME
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext Result.failure()

        try {
            setProgress(
                workDataOf(
                    UploadEnqueuer.KEY_PROGRESS to INDETERMINATE_PROGRESS,
                    UploadEnqueuer.KEY_DISPLAY_NAME to displayName
                )
            )
            setForeground(createForeground(displayName, INDETERMINATE_PROGRESS))

            val totalBytes = appContext.contentResolver
                .openAssetFileDescriptor(uri, "r")
                ?.use { it.length }
                ?: -1L

            val payload = readDocumentPayload(uri, totalBytes, displayName)
            val mimeType = appContext.contentResolver.getType(uri)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_MIME_TYPE
            val mediaType = mimeType.toMediaTypeOrNull() ?: DEFAULT_MIME_TYPE.toMediaType()

            val filePart = MultipartBody.Part.createFormData(
                "file",
                displayName,
                payload.bytes.toRequestBody(mediaType)
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
                        Result.retry()
                    } else {
                        Result.success(
                            workDataOf(
                                UploadEnqueuer.KEY_UPLOAD_ID to uploadId,
                                UploadEnqueuer.KEY_URI to uriString,
                                UploadEnqueuer.KEY_DISPLAY_NAME to displayName
                            )
                        )
                    }
                }
                413, 415 -> Result.failure()
                429 -> Result.retry()
                in 500..599 -> Result.retry()
                else -> Result.failure()
            }
        } catch (io: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Result.failure()
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
                        val progress = ((total * 100) / totalBytes).toInt().coerceIn(0, 100)
                        updateProgress(displayName, progress)
                    }
                }
            }
        }
        if (totalBytes <= 0) {
            updateProgress(displayName, INDETERMINATE_PROGRESS)
        } else {
            updateProgress(displayName, 100)
        }
        val sha256Hex = digest.digest().toHexString()
        return FilePayload(
            bytes = output.toByteArray(),
            size = total,
            sha256Hex = sha256Hex,
        )
    }

    private suspend fun updateProgress(displayName: String, progress: Int) {
        setProgress(
            workDataOf(
                UploadEnqueuer.KEY_PROGRESS to progress,
                UploadEnqueuer.KEY_DISPLAY_NAME to displayName
            )
        )
        setForeground(createForeground(displayName, progress))
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

    private fun String.toPlainRequestBody() =
        toRequestBody("text/plain".toMediaType())

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
