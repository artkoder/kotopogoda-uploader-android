package com.kotopogoda.uploader.core.network.work

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import androidx.work.workDataOf
import com.kotopogoda.uploader.core.network.KotopogodaApi
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import com.kotopogoda.uploader.core.network.upload.UploadForegroundKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: KotopogodaApi,
    private val foregroundDelegate: UploadForegroundDelegate
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
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

            val uploadedBytes = readDocumentBytes(uri, totalBytes, displayName)
            val payload = buildString {
                append("name=").append(displayName).append(';')
                append("uri=").append(uriString).append(';')
                append("size=").append(uploadedBytes).append(';')
                append("key=").append(idempotencyKey)
            }
            val response = api.uploadCatWeatherReport(payload)
            val uploadId = response.uploadId
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
        } catch (io: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Result.failure()
        }
    }

    private suspend fun readDocumentBytes(
        uri: Uri,
        totalBytes: Long,
        displayName: String
    ): Long {
        val resolver = appContext.contentResolver
        val inputStream = resolver.openInputStream(uri)
            ?: throw IOException("Unable to open input stream for $uri")
        var total = 0L
        inputStream.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) {
                    break
                }
                if (read > 0) {
                    total += read
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
        return total
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
        private const val DEFAULT_FILE_NAME = "photo.jpg"
        private const val INDETERMINATE_PROGRESS = -1
    }
}
