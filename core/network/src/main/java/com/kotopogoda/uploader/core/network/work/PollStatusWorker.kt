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
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.api.UploadStatusDto
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import com.kotopogoda.uploader.core.network.upload.UploadForegroundKind
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.work.UploadErrorKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
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
    private val uploadQueueRepository: UploadQueueRepository,
    private val foregroundDelegate: UploadForegroundDelegate,
    private val summaryStarter: UploadSummaryStarter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        summaryStarter.ensureRunning()
        val itemId = inputData.getLong(UploadEnqueuer.KEY_ITEM_ID, -1L)
            .takeIf { it >= 0 }
            ?: return@withContext Result.failure()
        val uploadId = inputData.getString(UploadEnqueuer.KEY_UPLOAD_ID)
            ?: return@withContext Result.failure()
        val uriString = inputData.getString(UploadEnqueuer.KEY_URI)
            ?: return@withContext Result.failure()
        val displayName = inputData.getString(UploadEnqueuer.KEY_DISPLAY_NAME) ?: DEFAULT_FILE_NAME
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext Result.failure()

        setForeground(createForeground(displayName))

        try {
            val response = try {
                uploadApi.getStatus(uploadId)
            } catch (io: UnknownHostException) {
                recordError(displayName, UploadErrorKind.NETWORK)
                uploadQueueRepository.updateProcessingHeartbeat(itemId)
                return@withContext Result.retry()
            } catch (io: IOException) {
                recordError(displayName, UploadErrorKind.NETWORK)
                uploadQueueRepository.updateProcessingHeartbeat(itemId)
                return@withContext Result.retry()
            }
            when (response.code()) {
                200 -> {
                    val body = response.body() ?: run {
                        uploadQueueRepository.updateProcessingHeartbeat(itemId)
                        return@withContext Result.retry()
                    }
                    when (resolveRemoteState(body)) {
                        RemoteState.QUEUED, RemoteState.PROCESSING -> {
                            uploadQueueRepository.updateProcessingHeartbeat(itemId)
                            maybeDelayForRetryAfter(response.headers())
                            Result.retry()
                        }
                        RemoteState.DONE -> handleCompletion(itemId, uri, uriString, displayName)
                        RemoteState.FAILED -> failureResult(
                            itemId = itemId,
                            displayName = displayName,
                            uriString = uriString,
                            errorKind = UploadErrorKind.REMOTE_FAILURE
                        )
                    }
                }
                404 -> failureResult(
                    itemId = itemId,
                    displayName = displayName,
                    uriString = uriString,
                    errorKind = UploadErrorKind.HTTP,
                    httpCode = response.code()
                )
                429 -> {
                    recordError(displayName, UploadErrorKind.HTTP, response.code())
                    uploadQueueRepository.updateProcessingHeartbeat(itemId)
                    maybeDelayForRetryAfter(response.headers())
                    Result.retry()
                }
                in 500..599 -> {
                    recordError(displayName, UploadErrorKind.HTTP, response.code())
                    uploadQueueRepository.updateProcessingHeartbeat(itemId)
                    maybeDelayForRetryAfter(response.headers())
                    Result.retry()
                }
                else -> failureResult(
                    itemId = itemId,
                    displayName = displayName,
                    uriString = uriString,
                    errorKind = UploadErrorKind.HTTP,
                    httpCode = response.code()
                )
            }
        } catch (io: IOException) {
            recordError(displayName, UploadErrorKind.NETWORK)
            uploadQueueRepository.updateProcessingHeartbeat(itemId)
            Result.retry()
        }
    }

    private suspend fun handleCompletion(
        itemId: Long,
        uri: Uri,
        uriString: String,
        displayName: String,
    ): Result {
        val completionState = deleteDocument(uri)
        recordCompletionState(completionState, displayName)
        uploadQueueRepository.markSucceeded(itemId)
        val output = Data.Builder()
            .putString(UploadEnqueuer.KEY_URI, uriString)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
            .putString(UploadEnqueuer.KEY_COMPLETION_STATE, completionState.toUploadState())
        when (completionState) {
            DeleteCompletionState.DELETED -> output.putBoolean(UploadEnqueuer.KEY_DELETED, true)
            DeleteCompletionState.AWAITING_MANUAL_DELETE -> output.putBoolean(UploadEnqueuer.KEY_DELETED, false)
            DeleteCompletionState.UNKNOWN -> Unit
        }
        return Result.success(output.build())
    }

    private fun deleteDocument(uri: Uri): DeleteCompletionState {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return DeleteCompletionState.UNKNOWN
            val deleted = runCatching { File(path).delete() }.getOrDefault(false)
            return if (deleted) DeleteCompletionState.DELETED else DeleteCompletionState.UNKNOWN
        }

        if (isMediaStoreUri(uri)) {
            return deleteMediaStoreDocument(uri)
        }

        val deleted = runCatching {
            DocumentFile.fromSingleUri(appContext, uri)?.delete() == true
        }.getOrDefault(false)
        return if (deleted) DeleteCompletionState.DELETED else DeleteCompletionState.UNKNOWN
    }

    private fun deleteMediaStoreDocument(uri: Uri): DeleteCompletionState {
        val resolver = appContext.contentResolver
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val deleted = runCatching { resolver.delete(uri, null, null) > 0 }
                .getOrDefault(false)
            return if (deleted) DeleteCompletionState.DELETED else DeleteCompletionState.UNKNOWN
        }

        return DeleteCompletionState.AWAITING_MANUAL_DELETE
    }

    private suspend fun recordCompletionState(state: DeleteCompletionState, displayName: String) {
        setProgress(
            workDataOf(
                UploadEnqueuer.KEY_COMPLETION_STATE to state.toUploadState(),
                UploadEnqueuer.KEY_DISPLAY_NAME to displayName,
            )
        )
    }

    private suspend fun recordError(
        displayName: String,
        errorKind: UploadErrorKind,
        httpCode: Int? = null
    ) {
        val builder = Data.Builder()
            .putInt(UploadEnqueuer.KEY_PROGRESS, INDETERMINATE_PROGRESS)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
            .putString(UploadEnqueuer.KEY_ERROR_KIND, errorKind.rawValue)
        httpCode?.let { builder.putInt(UploadEnqueuer.KEY_HTTP_CODE, it) }
        setProgress(builder.build())
    }

    private suspend fun failureResult(
        itemId: Long,
        displayName: String,
        uriString: String,
        errorKind: UploadErrorKind,
        httpCode: Int? = null
    ): Result {
        recordError(displayName, errorKind, httpCode)
        uploadQueueRepository.markFailed(
            id = itemId,
            errorKind = errorKind,
            httpCode = httpCode,
            requeue = false,
        )
        val builder = Data.Builder()
            .putString(UploadEnqueuer.KEY_URI, uriString)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
            .putString(UploadEnqueuer.KEY_ERROR_KIND, errorKind.rawValue)
        httpCode?.let { builder.putInt(UploadEnqueuer.KEY_HTTP_CODE, it) }
        return Result.failure(builder.build())
    }

    private fun isMediaStoreUri(uri: Uri): Boolean {
        return uri.authority == MediaStore.AUTHORITY
    }

    private enum class DeleteCompletionState {
        DELETED,
        AWAITING_MANUAL_DELETE,
        UNKNOWN,
    }

    private fun DeleteCompletionState.toUploadState(): String = when (this) {
        DeleteCompletionState.DELETED -> UploadEnqueuer.STATE_UPLOADED_DELETED
        DeleteCompletionState.AWAITING_MANUAL_DELETE -> UploadEnqueuer.STATE_UPLOADED_AWAITING_DELETE
        DeleteCompletionState.UNKNOWN -> UploadEnqueuer.STATE_UPLOAD_COMPLETED_UNKNOWN
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
