package com.kotopogoda.uploader.core.network.work

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.api.UploadStatusDto
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import com.kotopogoda.uploader.core.network.upload.UploadForegroundKind
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers

@HiltWorker
class PollStatusWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploadApi: UploadApi,
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
            val response = uploadApi.getStatus(uploadId)
            when (response.code()) {
                200 -> {
                    val body = response.body() ?: return@withContext Result.retry()
                    when (resolveRemoteState(body)) {
                        RemoteState.QUEUED, RemoteState.PROCESSING -> {
                            maybeDelayForRetryAfter(response.headers())
                            Result.retry()
                        }
                        RemoteState.DONE -> {
                            val deleted = deleteDocument(uri)
                            Result.success(
                                workDataOf(
                                    UploadEnqueuer.KEY_DELETED to deleted,
                                    UploadEnqueuer.KEY_URI to uriString,
                                    UploadEnqueuer.KEY_DISPLAY_NAME to displayName
                                )
                            )
                        }
                        RemoteState.FAILED -> Result.failure()
                    }
                }
                404 -> Result.failure()
                429 -> {
                    maybeDelayForRetryAfter(response.headers())
                    Result.retry()
                }
                in 500..599 -> {
                    maybeDelayForRetryAfter(response.headers())
                    Result.retry()
                }
                else -> Result.failure()
            }
        } catch (io: IOException) {
            Result.retry()
        }
    }

    private fun deleteDocument(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return false
            return runCatching { File(path).delete() }.getOrDefault(false)
        }
        return runCatching {
            DocumentFile.fromSingleUri(appContext, uri)?.delete() == true
        }.getOrDefault(false)
    }

    private fun createForeground(displayName: String): ForegroundInfo {
        return foregroundDelegate.create(displayName, INDETERMINATE_PROGRESS, id, UploadForegroundKind.POLL)
    }

    private fun resolveRemoteState(dto: UploadStatusDto): RemoteState {
        val normalized = dto.status?.lowercase(Locale.US)
        return when (normalized) {
            "queued" -> RemoteState.QUEUED
            "processing" -> RemoteState.PROCESSING
            "done" -> RemoteState.DONE
            "failed" -> RemoteState.FAILED
            else -> when {
                dto.error?.isNotBlank() == true -> RemoteState.FAILED
                dto.processed == true -> RemoteState.DONE
                else -> RemoteState.PROCESSING
            }
        }
    }

    private suspend fun maybeDelayForRetryAfter(headers: Headers) {
        val value = headers[RETRY_AFTER_HEADER] ?: return
        val delayMillis = parseRetryAfterMillis(value) ?: return
        if (delayMillis > 0) {
            delay(delayMillis)
        }
    }

    private fun parseRetryAfterMillis(raw: String): Long? {
        raw.trim().toLongOrNull()?.let { seconds ->
            return (seconds.coerceAtLeast(0)).times(1000)
        }
        return runCatching {
            val targetInstant = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw))
            val diff = targetInstant.toEpochMilli() - System.currentTimeMillis()
            diff.coerceAtLeast(0)
        }.getOrNull()
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "photo.jpg"
        private const val INDETERMINATE_PROGRESS = -1
        private const val RETRY_AFTER_HEADER = "Retry-After"
    }

    private enum class RemoteState {
        QUEUED,
        PROCESSING,
        DONE,
        FAILED,
    }
}
