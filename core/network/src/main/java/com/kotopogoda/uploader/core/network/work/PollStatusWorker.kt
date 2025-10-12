package com.kotopogoda.uploader.core.network.work

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kotopogoda.uploader.core.network.KotopogodaApi
import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import com.kotopogoda.uploader.core.network.upload.UploadForegroundKind
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

@HiltWorker
class PollStatusWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: KotopogodaApi,
    private val foregroundDelegate: UploadForegroundDelegate,
    private val summaryStarter: UploadSummaryStarter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        summaryStarter.ensureRunning()
        val uploadId = inputData.getString(UploadEnqueuer.KEY_UPLOAD_ID)
            ?: return@withContext Result.failure()
        val uriString = inputData.getString(UploadEnqueuer.KEY_URI)
            ?: return@withContext Result.failure()
        val displayName = inputData.getString(UploadEnqueuer.KEY_DISPLAY_NAME) ?: DEFAULT_FILE_NAME
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext Result.failure()

        setForeground(createForeground(displayName))

        try {
            val status = api.getUploadStatus(uploadId)
            if (status.failed) {
                return@withContext Result.failure()
            }
            if (!status.processed) {
                return@withContext Result.retry()
            }

            val deleted = deleteDocument(uri)
            Result.success(
                workDataOf(
                    UploadEnqueuer.KEY_DELETED to deleted,
                    UploadEnqueuer.KEY_URI to uriString,
                    UploadEnqueuer.KEY_DISPLAY_NAME to displayName
                )
            )
        } catch (io: IOException) {
            Result.retry()
        } catch (http: HttpException) {
            if (http.code() in 500..599) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun deleteDocument(uri: Uri): Boolean {
        return runCatching {
            DocumentFile.fromSingleUri(appContext, uri)?.delete() == true
        }.getOrDefault(false)
    }

    private fun createForeground(displayName: String): ForegroundInfo {
        return foregroundDelegate.create(displayName, INDETERMINATE_PROGRESS, id, UploadForegroundKind.POLL)
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "photo.jpg"
        private const val INDETERMINATE_PROGRESS = -1
    }
}
