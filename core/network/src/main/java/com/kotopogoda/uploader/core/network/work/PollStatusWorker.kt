package com.kotopogoda.uploader.core.network.work

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
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
                        RemoteState.DONE -> when (val outcome = deleteDocument(uri)) {
                            is DeleteOutcome.Completed -> {
                                val output = Data.Builder()
                                    .putString(UploadEnqueuer.KEY_URI, uriString)
                                    .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
                                when (outcome.state) {
                                    DeleteCompletionState.DELETED -> output.putBoolean(UploadEnqueuer.KEY_DELETED, true)
                                    DeleteCompletionState.USER_DECLINED -> output.putBoolean(UploadEnqueuer.KEY_DELETED, false)
                                    DeleteCompletionState.UNKNOWN -> Unit
                                }
                                Result.success(output.build())
                            }
                            DeleteOutcome.WaitingForUserConfirmation -> Result.retry()
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

    private suspend fun deleteDocument(uri: Uri): DeleteOutcome {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return DeleteOutcome.Completed(DeleteCompletionState.UNKNOWN)
            val deleted = runCatching { File(path).delete() }.getOrDefault(false)
            return DeleteOutcome.Completed(if (deleted) DeleteCompletionState.DELETED else DeleteCompletionState.UNKNOWN)
        }

        if (isMediaStoreUri(uri)) {
            return deleteMediaStoreDocument(uri)
        }

        val deleted = runCatching {
            DocumentFile.fromSingleUri(appContext, uri)?.delete() == true
        }.getOrDefault(false)
        return DeleteOutcome.Completed(if (deleted) DeleteCompletionState.DELETED else DeleteCompletionState.UNKNOWN)
    }

    private suspend fun deleteMediaStoreDocument(uri: Uri): DeleteOutcome {
        val resolver = appContext.contentResolver
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val deleted = runCatching { resolver.delete(uri, null, null) > 0 }
                .getOrDefault(false)
            return DeleteOutcome.Completed(if (deleted) DeleteCompletionState.DELETED else DeleteCompletionState.UNKNOWN)
        }

        val progress = loadCurrentProgress()
        val status = progress.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS)
            ?: DeleteRequestContract.STATUS_NONE
        val storedIntentBytes = progress.getByteArray(DeleteRequestContract.KEY_PENDING_DELETE_INTENT)
            ?.takeIf { it.isNotEmpty() }

        when (status) {
            DeleteRequestContract.STATUS_CONFIRMED -> {
                clearPendingDeleteState()
                val deleted = !isMediaStoreEntryPresent(resolver, uri)
                return DeleteOutcome.Completed(if (deleted) DeleteCompletionState.DELETED else DeleteCompletionState.UNKNOWN)
            }

            DeleteRequestContract.STATUS_DECLINED -> {
                clearPendingDeleteState()
                return DeleteOutcome.Completed(DeleteCompletionState.USER_DECLINED)
            }

            DeleteRequestContract.STATUS_PENDING -> {
                if (storedIntentBytes != null) {
                    val now = System.currentTimeMillis()
                    val lastLaunch = progress.getLong(DeleteRequestContract.KEY_PENDING_DELETE_LAST_LAUNCH, 0L)
                    val shouldRelaunch = now - lastLaunch >= DeleteRequestContract.DELETE_RELAUNCH_INTERVAL_MILLIS
                    if (shouldRelaunch) {
                        updateLastLaunch(now)
                        DeleteRequestHelperActivity.launch(appContext, id, storedIntentBytes)
                    }
                    return DeleteOutcome.WaitingForUserConfirmation
                }
                clearPendingDeleteState()
            }
        }

        return runCatching {
            val pendingIntent = MediaStore.createDeleteRequest(resolver, listOf(uri))
            val serialized = PendingIntentSerializer.serialize(pendingIntent)
            setProgress(
                workDataOf(
                    DeleteRequestContract.KEY_PENDING_DELETE_STATUS to DeleteRequestContract.STATUS_PENDING,
                    DeleteRequestContract.KEY_PENDING_DELETE_INTENT to serialized,
                    DeleteRequestContract.KEY_PENDING_DELETE_LAST_LAUNCH to System.currentTimeMillis()
                )
            )
            DeleteRequestHelperActivity.launch(appContext, id, serialized)
            DeleteOutcome.WaitingForUserConfirmation
        }.getOrElse {
            clearPendingDeleteState()
            DeleteOutcome.Completed(DeleteCompletionState.UNKNOWN)
        }
    }

    private suspend fun loadCurrentProgress(): Data {
        return runCatching {
            WorkManager.getInstance(appContext).getWorkInfoById(id).await()?.progress ?: Data.EMPTY
        }.getOrDefault(Data.EMPTY)
    }

    private suspend fun clearPendingDeleteState() {
        setProgress(
            workDataOf(
                DeleteRequestContract.KEY_PENDING_DELETE_STATUS to DeleteRequestContract.STATUS_NONE,
                DeleteRequestContract.KEY_PENDING_DELETE_INTENT to ByteArray(0),
                DeleteRequestContract.KEY_PENDING_DELETE_LAST_LAUNCH to 0L
            )
        )
    }

    private suspend fun updateLastLaunch(timestamp: Long) {
        setProgress(workDataOf(DeleteRequestContract.KEY_PENDING_DELETE_LAST_LAUNCH to timestamp))
    }

    private fun isMediaStoreEntryPresent(resolver: ContentResolver, uri: Uri): Boolean {
        return runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { cursor -> cursor.moveToFirst() }
        }.getOrDefault(false)
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        return uri.authority == MediaStore.AUTHORITY
    }

    private enum class DeleteCompletionState {
        DELETED,
        USER_DECLINED,
        UNKNOWN,
    }

    private sealed interface DeleteOutcome {
        data class Completed(val state: DeleteCompletionState) : DeleteOutcome
        data object WaitingForUserConfirmation : DeleteOutcome
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
