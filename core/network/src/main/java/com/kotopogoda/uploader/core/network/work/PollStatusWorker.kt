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
import com.kotopogoda.uploader.core.data.upload.UploadLog
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
import timber.log.Timber

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

        Timber.tag("WorkManager").i(
            pollLogMessage(
                action = "poll_worker_start",
                itemId = itemId,
                uploadId = uploadId,
                uri = uri,
                details = arrayOf(
                    "display_name" to displayName,
                ),
            ),
        )

        setForeground(createForeground(displayName))

        try {
            val response = try {
                uploadApi.getStatus(uploadId)
            } catch (io: UnknownHostException) {
                Timber.tag("WorkManager").w(
                    pollLogMessage(
                        action = "poll_request_error",
                        itemId = itemId,
                        uploadId = uploadId,
                        uri = uri,
                        details = arrayOf(
                            "reason" to "unknown_host",
                        ),
                    ),
                )
                recordError(displayName, UploadErrorKind.NETWORK)
                uploadQueueRepository.updateProcessingHeartbeat(itemId)
                return@withContext Result.retry()
            } catch (io: IOException) {
                Timber.tag("WorkManager").w(
                    pollLogMessage(
                        action = "poll_request_error",
                        itemId = itemId,
                        uploadId = uploadId,
                        uri = uri,
                        details = arrayOf(
                            "reason" to (io::class.simpleName ?: "io_exception"),
                        ),
                    ),
                )
                recordError(displayName, UploadErrorKind.NETWORK)
                uploadQueueRepository.updateProcessingHeartbeat(itemId)
                return@withContext Result.retry()
            }
            when (response.code()) {
                200 -> {
                    val body = response.body() ?: run {
                        Timber.tag("WorkManager").w(
                            pollLogMessage(
                                action = "poll_status_response_body_missing",
                                itemId = itemId,
                                uploadId = uploadId,
                                uri = uri,
                                details = arrayOf(
                                    "http_code" to response.code(),
                                ),
                            ),
                        )
                        uploadQueueRepository.updateProcessingHeartbeat(itemId)
                        return@withContext Result.retry()
                    }
                    val remoteState = resolveRemoteState(body)
                    when (remoteState) {
                        RemoteState.QUEUED, RemoteState.PROCESSING -> {
                            Timber.tag("WorkManager").i(
                                pollLogMessage(
                                    action = "poll_status_pending",
                                    itemId = itemId,
                                    uploadId = uploadId,
                                    uri = uri,
                                    details = arrayOf(
                                        "http_code" to response.code(),
                                        "remote_state" to remoteState.name.lowercase(Locale.US),
                                        "retry" to true,
                                    ),
                                ),
                            )
                            uploadQueueRepository.updateProcessingHeartbeat(itemId)
                            maybeDelayForRetryAfter(response.headers())
                            Result.retry()
                        }
                        RemoteState.DONE -> {
                            Timber.tag("WorkManager").i(
                                pollLogMessage(
                                    action = "poll_status_done",
                                    itemId = itemId,
                                    uploadId = uploadId,
                                    uri = uri,
                                    details = arrayOf(
                                        "http_code" to response.code(),
                                    ),
                                ),
                            )
                            handleCompletion(
                                itemId = itemId,
                                uploadId = uploadId,
                                uri = uri,
                                uriString = uriString,
                                displayName = displayName,
                            )
                        }
                        RemoteState.FAILED -> {
                            Timber.tag("WorkManager").w(
                                pollLogMessage(
                                    action = "poll_status_failed",
                                    itemId = itemId,
                                    uploadId = uploadId,
                                    uri = uri,
                                    details = arrayOf(
                                        "http_code" to response.code(),
                                        "remote_state" to remoteState.name.lowercase(Locale.US),
                                    ),
                                ),
                            )
                            failureResult(
                                itemId = itemId,
                                uploadId = uploadId,
                                displayName = displayName,
                                uriString = uriString,
                                errorKind = UploadErrorKind.REMOTE_FAILURE
                            )
                        }
                    }
                }
                404 -> {
                    Timber.tag("WorkManager").w(
                        pollLogMessage(
                            action = "poll_status_not_found",
                            itemId = itemId,
                            uploadId = uploadId,
                            uri = uri,
                            details = arrayOf(
                                "http_code" to response.code(),
                            ),
                        ),
                    )
                    failureResult(
                        itemId = itemId,
                        uploadId = uploadId,
                        displayName = displayName,
                        uriString = uriString,
                        errorKind = UploadErrorKind.HTTP,
                        httpCode = response.code()
                    )
                }
                429 -> {
                    val retryAfter = response.headers()[RETRY_AFTER_HEADER]
                    Timber.tag("WorkManager").w(
                        pollLogMessage(
                            action = "poll_status_throttled",
                            itemId = itemId,
                            uploadId = uploadId,
                            uri = uri,
                            details = arrayOf(
                                "http_code" to response.code(),
                                "retry_after" to retryAfter,
                            ),
                        ),
                    )
                    recordError(displayName, UploadErrorKind.HTTP, response.code())
                    uploadQueueRepository.updateProcessingHeartbeat(itemId)
                    maybeDelayForRetryAfter(response.headers())
                    Result.retry()
                }
                in 500..599 -> {
                    val retryAfter = response.headers()[RETRY_AFTER_HEADER]
                    Timber.tag("WorkManager").w(
                        pollLogMessage(
                            action = "poll_status_server_error",
                            itemId = itemId,
                            uploadId = uploadId,
                            uri = uri,
                            details = arrayOf(
                                "http_code" to response.code(),
                                "retry_after" to retryAfter,
                            ),
                        ),
                    )
                    recordError(displayName, UploadErrorKind.HTTP, response.code())
                    uploadQueueRepository.updateProcessingHeartbeat(itemId)
                    maybeDelayForRetryAfter(response.headers())
                    Result.retry()
                }
                else -> {
                    Timber.tag("WorkManager").w(
                        pollLogMessage(
                            action = "poll_status_unexpected",
                            itemId = itemId,
                            uploadId = uploadId,
                            uri = uri,
                            details = arrayOf(
                                "http_code" to response.code(),
                            ),
                        ),
                    )
                    failureResult(
                        itemId = itemId,
                        uploadId = uploadId,
                        displayName = displayName,
                        uriString = uriString,
                        errorKind = UploadErrorKind.HTTP,
                        httpCode = response.code()
                    )
                }
            }
        } catch (io: IOException) {
            Timber.tag("WorkManager").w(
                pollLogMessage(
                    action = "poll_request_error",
                    itemId = itemId,
                    uploadId = uploadId,
                    uri = uri,
                    details = arrayOf(
                        "reason" to (io::class.simpleName ?: "io_exception"),
                    ),
                ),
            )
            recordError(displayName, UploadErrorKind.NETWORK)
            uploadQueueRepository.updateProcessingHeartbeat(itemId)
            Result.retry()
        }
    }

    private suspend fun handleCompletion(
        itemId: Long,
        uploadId: String,
        uri: Uri,
        uriString: String,
        displayName: String,
    ): Result {
        val completionState = deleteDocument(uri)
        recordCompletionState(completionState, displayName)
        uploadQueueRepository.markSucceeded(itemId)
        Timber.tag("WorkManager").i(
            pollLogMessage(
                action = "poll_worker_complete",
                itemId = itemId,
                uploadId = uploadId,
                uri = uri,
                details = arrayOf(
                    "result" to completionState.name.lowercase(Locale.US),
                    "display_name" to displayName,
                ),
            ),
        )
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
        Timber.tag("WorkManager").v(
            pollLogMessage(
                action = "poll_progress_completion_state",
                details = arrayOf(
                    "state" to state.name.lowercase(Locale.US),
                    "display_name" to displayName,
                ),
            ),
        )
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
        Timber.tag("WorkManager").v(
            pollLogMessage(
                action = "poll_progress_error",
                details = arrayOf(
                    "display_name" to displayName,
                    "error_kind" to errorKind,
                    "http_code" to httpCode,
                ),
            ),
        )
        val builder = Data.Builder()
            .putInt(UploadEnqueuer.KEY_PROGRESS, INDETERMINATE_PROGRESS)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
            .putString(UploadEnqueuer.KEY_ERROR_KIND, errorKind.rawValue)
        httpCode?.let { builder.putInt(UploadEnqueuer.KEY_HTTP_CODE, it) }
        setProgress(builder.build())
    }

    private suspend fun failureResult(
        itemId: Long,
        uploadId: String,
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
        val parsedUri = runCatching { Uri.parse(uriString) }.getOrNull()
        Timber.tag("WorkManager").w(
            pollLogMessage(
                action = "poll_worker_complete",
                itemId = itemId,
                uploadId = uploadId,
                uri = parsedUri,
                details = arrayOf(
                    "result" to "error",
                    "error_kind" to errorKind,
                    "http_code" to httpCode,
                    "display_name" to displayName,
                ),
            ),
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

private fun pollLogMessage(
    action: String,
    itemId: Long? = null,
    uploadId: String? = null,
    uri: Uri? = null,
    details: Array<out Pair<String, Any?>> = emptyArray(),
): String {
    val normalizedDetails = buildList {
        itemId?.let { add("queue_item_id" to it) }
        addAll(details)
    }.toTypedArray()
    return UploadLog.message(
        category = "UPLOAD/Poll",
        action = action,
        photoId = uploadId,
        uri = uri,
        details = normalizedDetails,
    )
}
