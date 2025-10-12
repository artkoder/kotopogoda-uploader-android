package com.kotopogoda.uploader.core.network.work

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.network.KotopogodaApi
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: KotopogodaApi
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uriString = inputData.getString(UploadEnqueuer.KEY_URI)
            ?: return@withContext Result.failure()
        val idempotencyKey = inputData.getString(UploadEnqueuer.KEY_IDEMPOTENCY_KEY)
            ?: return@withContext Result.failure()
        val displayName = inputData.getString(UploadEnqueuer.KEY_DISPLAY_NAME) ?: DEFAULT_FILE_NAME
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext Result.failure()

        try {
            val bytes = readDocumentBytes(uri)
            val payload = buildString {
                append("name=").append(displayName).append(';')
                append("uri=").append(uriString).append(';')
                append("size=").append(bytes).append(';')
                append("key=").append(idempotencyKey)
            }
            val success = api.uploadCatWeatherReport(payload)
            if (success) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (io: IOException) {
            Result.retry()
        } catch (error: Exception) {
            Result.failure()
        }
    }

    private fun readDocumentBytes(uri: Uri): Long {
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
                }
            }
        }
        return total
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_FILE_NAME = "photo.jpg"
    }
}
