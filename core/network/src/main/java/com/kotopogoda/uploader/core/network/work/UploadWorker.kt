package com.kotopogoda.uploader.core.network.work

import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kotopogoda.uploader.core.data.ocr.OcrQuotaRepository
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.data.util.logUriReadDebug
import com.kotopogoda.uploader.core.data.util.requireOriginalIfNeeded
import com.kotopogoda.uploader.core.network.api.UploadAcceptedDto
import com.kotopogoda.uploader.core.network.api.UploadLookupDto
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.upload.UploadConstraintsProvider
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import com.kotopogoda.uploader.core.network.upload.UploadForegroundKind
import com.kotopogoda.uploader.core.network.upload.UploadRequestPayload
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.prepareUploadRequestPayload
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.core.work.WorkManagerProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import retrofit2.Response
import timber.log.Timber
import org.json.JSONObject

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploadApi: UploadApi,
    private val uploadQueueRepository: UploadQueueRepository,
    private val foregroundDelegate: UploadForegroundDelegate,
    private val summaryStarter: UploadSummaryStarter,
    private val ocrQuotaRepository: OcrQuotaRepository,
    private val workManagerProvider: WorkManagerProvider,
    private val constraintsProvider: UploadConstraintsProvider,
) : CoroutineWorker(appContext, params) {

    private var lastProgressSnapshot = ProgressSnapshot()
    private var currentItemId: Long? = null

    override suspend fun doWork(): Result {
        val itemId = inputData.getLong(UploadEnqueuer.KEY_ITEM_ID, -1L)
            .takeIf { it >= 0 }
            ?: return Result.failure()
        val uriString = inputData.getString(UploadEnqueuer.KEY_URI)
            ?: return Result.failure()
        val idempotencyKey = inputData.getString(UploadEnqueuer.KEY_IDEMPOTENCY_KEY)
            ?: return Result.failure()
        val displayName = inputData.getString(UploadEnqueuer.KEY_DISPLAY_NAME) ?: DEFAULT_FILE_NAME
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return Result.failure()

        Timber.tag("WorkManager").i(
            UploadLog.message(
                category = CATEGORY_UPLOAD_START,
                action = "upload_worker_start",
                uri = uri,
                details = arrayOf(
                    "queue_item_id" to itemId,
                    "display_name" to displayName,
                ),
            )
        )

        currentItemId = itemId
        ensureSummaryRunning(displayName, uri)
        try {
            val totalBytes = resolveContentSize(uri)
            Timber.tag("WorkManager").i(
                UploadLog.message(
                    category = CATEGORY_UPLOAD_CONTENT,
                    action = "content_length_resolved",
                    uri = uri,
                    details = arrayOf(
                        "queue_item_id" to itemId,
                        "display_name" to displayName,
                        "length" to totalBytes,
                    ),
                ),
            )

            updateProgress(
                displayName = displayName,
                progress = INDETERMINATE_PROGRESS,
                bytesSent = if (totalBytes > 0) 0L else null,
                totalBytes = totalBytes.takeIf { it > 0 }
            )

            val mimeType = withContext(Dispatchers.IO) {
                appContext.contentResolver.getType(uri)
            }?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME_TYPE
            val mediaType = mimeType.toMediaTypeOrNull() ?: DEFAULT_MIME_TYPE.toMediaType()
            val payload = prepareUploadPayload(
                uri = uri,
                displayName = displayName,
                idempotencyKey = idempotencyKey,
                totalBytes = totalBytes,
                mediaType = mediaType,
                mimeType = mimeType,
            )
            Timber.tag("WorkManager").i(
                UploadLog.message(
                    category = CATEGORY_UPLOAD_CONTENT,
                    action = "payload_ready",
                    uri = uri,
                    details = arrayOf(
                        "queue_item_id" to itemId,
                        "display_name" to displayName,
                        "size" to payload.fileSize,
                        "file_sha256" to payload.fileSha256Hex,
                        "request_sha256" to payload.requestSha256Hex,
                    ),
                ),
            )
            Timber.tag("WorkManager").i(
                UploadLog.message(
                    category = CATEGORY_UPLOAD_PREPARE,
                    action = "upload_prepare_request",
                    uri = uri,
                    details = arrayOf(
                        "queue_item_id" to itemId,
                        "display_name" to displayName,
                        "mime_type" to mimeType,
                        "size" to payload.fileSize,
                    ),
                )
            )

            var lastReportedPercent = -1
            var lastBytesSent: Long? = null
            val requestBody = payload.createRequestBody { bytesSent, _ ->
                val percent = if (payload.fileSize > 0) {
                    ((bytesSent * 100) / payload.fileSize).toInt().coerceIn(0, 100)
                } else {
                    100
                }
                if (percent != lastReportedPercent) {
                    lastReportedPercent = percent
                    runBlocking {
                        lastBytesSent = bytesSent
                        Timber.tag("WorkManager").i(
                            UploadLog.message(
                                category = CATEGORY_UPLOAD_PROGRESS,
                                action = "upload_progress",
                                uri = uri,
                                details = buildList {
                                    add("queue_item_id" to itemId)
                                    add("display_name" to displayName)
                                    add("progress" to percent)
                                    add("bytes_sent" to bytesSent)
                                    payload.fileSize.takeIf { it > 0 }?.let { add("total_bytes" to it) }
                                }.toTypedArray(),
                            )
                        )
                        updateProgress(
                            displayName = displayName,
                            progress = percent,
                            bytesSent = bytesSent,
                            totalBytes = payload.fileSize.takeIf { it > 0 }
                        )
                    }
                }
            }
            Timber.tag("WorkManager").i(
                UploadLog.message(
                    category = CATEGORY_HTTP_REQUEST,
                    action = "upload_request_send",
                    uri = uri,
                    details = arrayOf(
                        "queue_item_id" to itemId,
                        "display_name" to displayName,
                        "idempotency_key" to idempotencyKey,
                    ),
                )
            )
            val response = try {
                executeUpload(
                    idempotencyKey = idempotencyKey,
                    payload = payload,
                    requestBody = requestBody,
                )
            } catch (timeout: SocketTimeoutException) {
                logUploadError(
                    displayName = displayName,
                    errorKind = UploadErrorKind.NETWORK,
                    uri = uri,
                    throwable = timeout,
                )
                return retryResult(
                    displayName = displayName,
                    errorKind = UploadErrorKind.NETWORK,
                    uri = uri,
                    throwable = timeout,
                )
            } catch (io: UnknownHostException) {
                logUploadError(
                    displayName = displayName,
                    errorKind = UploadErrorKind.NETWORK,
                    uri = uri,
                    throwable = io,
                )
                return retryResult(
                    displayName = displayName,
                    errorKind = UploadErrorKind.NETWORK,
                    uri = uri,
                    throwable = io,
                )
            } catch (io: IOException) {
                logUploadError(
                    displayName = displayName,
                    errorKind = UploadErrorKind.NETWORK,
                    uri = uri,
                    throwable = io,
                )
                return retryResult(
                    displayName = displayName,
                    errorKind = UploadErrorKind.NETWORK,
                    uri = uri,
                    throwable = io,
                )
            }

            val responseCode = response.code()
            val errorMessage = if (responseCode in 400..499) {
                response.extractErrorMessage()
            } else {
                null
            }
            Timber.tag("WorkManager").i(
                UploadLog.message(
                    category = CATEGORY_HTTP_RESPONSE,
                    action = "upload_response_code",
                    uri = uri,
                    details = buildList {
                        add("queue_item_id" to itemId)
                        add("display_name" to displayName)
                        add("http_code" to responseCode)
                        if (!errorMessage.isNullOrBlank()) {
                            add("error_message" to errorMessage)
                        }
                    }.toTypedArray(),
                )
            )

            val result = when (responseCode) {
                202, 409 -> {
                    val initialAcceptance = response.body()
                    if (responseCode == 202) {
                        initialAcceptance?.ocrRemainingPercent?.let { percent ->
                            val normalized = percent.coerceIn(0, 100)
                            runCatching {
                                ocrQuotaRepository.updatePercent(normalized)
                            }.onSuccess {
                                Timber.tag("WorkManager").i(
                                    UploadLog.message(
                                        category = CATEGORY_UPLOAD_SUCCESS,
                                        action = "upload_ocr_quota_updated",
                                        uri = uri,
                                        details = arrayOf(
                                            "queue_item_id" to itemId,
                                            "display_name" to displayName,
                                            "ocr_remaining_percent" to normalized,
                                        ),
                                    ),
                                )
                            }.onFailure { error ->
                                Timber.tag("WorkManager").w(
                                    error,
                                    UploadLog.message(
                                        category = CATEGORY_UPLOAD_ERROR,
                                        action = "upload_ocr_quota_update_failed",
                                        uri = uri,
                                        details = arrayOf(
                                            "queue_item_id" to itemId,
                                            "display_name" to displayName,
                                            "ocr_remaining_percent" to normalized,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                    var resolution: UploadResolution? = null
                    var acceptanceSkipReason: AcceptanceSkipReason? = null
                    var acceptanceError: Throwable? = null
                    try {
                        resolution = resolveUploadAcceptance(
                            itemId = itemId,
                            displayName = displayName,
                            uri = uri,
                            idempotencyKey = idempotencyKey,
                            initial = initialAcceptance,
                        )
                    } catch (timeout: SocketTimeoutException) {
                        logUploadError(displayName, UploadErrorKind.NETWORK, uri, timeout)
                        acceptanceSkipReason = AcceptanceSkipReason.NETWORK
                        acceptanceError = timeout
                    } catch (io: UnknownHostException) {
                        logUploadError(displayName, UploadErrorKind.NETWORK, uri, io)
                        acceptanceSkipReason = AcceptanceSkipReason.NETWORK
                        acceptanceError = io
                    } catch (io: IOException) {
                        logUploadError(displayName, UploadErrorKind.NETWORK, uri, io)
                        acceptanceSkipReason = AcceptanceSkipReason.NETWORK
                        acceptanceError = io
                    }

                    if (resolution == null && acceptanceSkipReason == null) {
                        acceptanceSkipReason = AcceptanceSkipReason.MISSING_UPLOAD_ID
                    }
                    acceptanceSkipReason?.let { reason ->
                        logPostNotRetried(
                            itemId = itemId,
                            displayName = displayName,
                            uri = uri,
                            idempotencyKey = idempotencyKey,
                            reason = reason,
                            throwable = acceptanceError,
                        )
                    }

                    val uploadId = resolution?.uploadId
                    val acceptanceStatus = resolution?.status ?: initialAcceptance?.status

                    updateProgress(
                        displayName = displayName,
                        progress = 100,
                        bytesSent = lastBytesSent ?: payload.fileSize,
                        totalBytes = payload.fileSize.takeIf { it > 0 },
                        completionState = UploadEnqueuer.STATE_UPLOAD_COMPLETED_UNKNOWN,
                    )
                    val resultData = buildResultData(
                        displayName = displayName,
                        uriString = uriString,
                        uploadId = uploadId,
                        bytesSent = lastProgressSnapshot.bytesSent,
                        totalBytes = lastProgressSnapshot.totalBytes,
                        completionState = UploadEnqueuer.STATE_UPLOAD_COMPLETED_UNKNOWN,
                    )
                    Timber.tag("WorkManager").i(
                        UploadLog.message(
                            category = CATEGORY_UPLOAD_SUCCESS,
                            action = "upload_worker_success",
                            uri = uri,
                            details = buildList {
                                add("queue_item_id" to itemId)
                                add("display_name" to displayName)
                                uploadId?.let { add("upload_id" to it) }
                                acceptanceStatus?.let { add("status" to it) }
                                add("bytes_sent" to (lastProgressSnapshot.bytesSent ?: payload.fileSize))
                            }.toTypedArray(),
                        )
                    )
                    recordAcceptance(
                        itemId = itemId,
                        displayName = displayName,
                        uri = uri,
                        uploadId = uploadId,
                    )
                    enqueuePollWork(
                        itemId = itemId,
                        uploadId = uploadId,
                        uriString = uriString,
                        displayName = displayName,
                        idempotencyKey = idempotencyKey,
                        uri = uri
                    )
                    Result.success(resultData)
                }
                401, 403 -> failureResult(
                    itemId = itemId,
                    displayName = displayName,
                    uriString = uriString,
                    errorKind = UploadErrorKind.AUTH,
                    httpCode = responseCode,
                    errorMessage = errorMessage,
                )
                413, 415 -> failureResult(
                    itemId = itemId,
                    displayName = displayName,
                    uriString = uriString,
                    errorKind = UploadErrorKind.HTTP,
                    httpCode = responseCode,
                    errorMessage = errorMessage,
                )
                429 -> retryResult(
                    displayName = displayName,
                    errorKind = UploadErrorKind.HTTP,
                    httpCode = responseCode,
                    uri = uri,
                    headers = response.headers(),
                )
                in 500..599 -> retryResult(
                    displayName = displayName,
                    errorKind = UploadErrorKind.HTTP,
                    httpCode = responseCode,
                    uri = uri,
                    headers = response.headers(),
                )
                else -> failureResult(
                    itemId = itemId,
                    displayName = displayName,
                    uriString = uriString,
                    errorKind = UploadErrorKind.HTTP,
                    httpCode = responseCode,
                    errorMessage = errorMessage,
                )
            }
            return result
        } catch (security: RecoverableSecurityException) {
            logUploadError(displayName, UploadErrorKind.IO, uri, security)
            return failureResult(itemId, displayName, uriString, UploadErrorKind.IO, throwable = security)
        } catch (security: SecurityException) {
            logUploadError(displayName, UploadErrorKind.IO, uri, security)
            return failureResult(itemId, displayName, uriString, UploadErrorKind.IO, throwable = security)
        } catch (notFound: FileNotFoundException) {
            logUploadError(displayName, UploadErrorKind.IO, uri, notFound)
            return failureResult(itemId, displayName, uriString, UploadErrorKind.IO, throwable = notFound)
        } catch (io: IOException) {
            logUploadError(displayName, UploadErrorKind.IO, uri, io)
            return retryResult(displayName, UploadErrorKind.IO, uri = uri, throwable = io)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logUploadError(displayName, UploadErrorKind.UNEXPECTED, uri, error)
            return failureResult(itemId, displayName, uriString, UploadErrorKind.UNEXPECTED, throwable = error)
        } finally {
            currentItemId = null
        }
    }

    private suspend fun prepareUploadPayload(
        uri: Uri,
        displayName: String,
        idempotencyKey: String,
        totalBytes: Long,
        mediaType: MediaType,
        mimeType: String,
    ): UploadRequestPayload {
        return prepareUploadRequestPayload(
            resolver = appContext.contentResolver,
            uri = uri,
            displayName = displayName,
            mimeType = mimeType,
            mediaType = mediaType,
            totalBytes = totalBytes,
            boundarySeed = idempotencyKey,
        )
    }

    private suspend fun updateProgress(
        displayName: String,
        progress: Int,
        bytesSent: Long? = lastProgressSnapshot.bytesSent,
        totalBytes: Long? = lastProgressSnapshot.totalBytes,
        errorKind: UploadErrorKind? = null,
        httpCode: Int? = null,
        completionState: String? = null,
    ) {
        currentItemId?.let {
            try {
                uploadQueueRepository.updateProcessingHeartbeat(it)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logGuardError(
                    action = "update_heartbeat_failed",
                    details = guardDetails(displayName = displayName),
                    throwable = error,
                )
            }
        }
        val normalizedProgress = if (progress == INDETERMINATE_PROGRESS) {
            INDETERMINATE_PROGRESS
        } else {
            progress.coerceIn(0, 100)
        }
        val resolvedProgress = when {
            normalizedProgress == INDETERMINATE_PROGRESS -> INDETERMINATE_PROGRESS
            lastProgressSnapshot.progress == INDETERMINATE_PROGRESS -> normalizedProgress
            else -> maxOf(normalizedProgress, lastProgressSnapshot.progress)
        }
        val resolvedBytesSent = when {
            bytesSent == null -> lastProgressSnapshot.bytesSent
            lastProgressSnapshot.bytesSent == null -> bytesSent
            else -> maxOf(bytesSent, lastProgressSnapshot.bytesSent!!)
        }
        val resolvedTotalBytes = totalBytes ?: lastProgressSnapshot.totalBytes

        lastProgressSnapshot = ProgressSnapshot(
            progress = resolvedProgress,
            bytesSent = resolvedBytesSent,
            totalBytes = resolvedTotalBytes
        )
        val builder = Data.Builder()
            .putInt(UploadEnqueuer.KEY_PROGRESS, resolvedProgress)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
        resolvedBytesSent?.let { builder.putLong(UploadEnqueuer.KEY_BYTES_SENT, it) }
        resolvedTotalBytes?.let { builder.putLong(UploadEnqueuer.KEY_TOTAL_BYTES, it) }
        errorKind?.let { builder.putString(UploadEnqueuer.KEY_ERROR_KIND, it.rawValue) }
        httpCode?.let { builder.putInt(UploadEnqueuer.KEY_HTTP_CODE, it) }
        completionState?.let { builder.putString(UploadEnqueuer.KEY_COMPLETION_STATE, it) }
        val progressData = builder.build()
        val additionalDetails = mutableListOf<Pair<String, Any?>>()
        completionState?.let { additionalDetails += "completion_state" to it }
        val details = guardDetails(
            displayName = displayName,
            progress = resolvedProgress,
            bytesSent = resolvedBytesSent,
            totalBytes = resolvedTotalBytes,
            errorKind = errorKind,
            httpCode = httpCode,
            additional = additionalDetails,
        )
        try {
            setProgress(progressData)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logGuardError(
                action = "set_progress_failed",
                details = details,
                throwable = error,
            )
        }
        val foreground = try {
            createForeground(displayName, resolvedProgress)
        } catch (error: Exception) {
            logGuardError(
                action = "create_foreground_failed",
                details = details,
                throwable = error,
            )
            null
        }
        if (foreground != null) {
            try {
                setForeground(foreground)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logGuardError(
                    action = "set_foreground_failed",
                    details = details,
                    throwable = error,
                )
            }
        }

        Timber.tag("WorkManager").i(
            UploadLog.message(
                category = CATEGORY_UPLOAD_PROGRESS_STATE,
                action = "upload_progress_state",
                details = details.toTypedArray(),
            ),
        )
    }

    private suspend fun resolveUploadAcceptance(
        itemId: Long,
        displayName: String,
        uri: Uri,
        idempotencyKey: String,
        initial: UploadAcceptedDto?,
    ): UploadResolution? {
        val initialUploadId = initial?.uploadId
        if (!initialUploadId.isNullOrBlank()) {
            return UploadResolution(initialUploadId, initial.status)
        }

        Timber.tag("WorkManager").i(
            UploadLog.message(
                category = CATEGORY_HTTP_REQUEST,
                action = "upload_reconcile_request",
                uri = uri,
                details = arrayOf(
                    "queue_item_id" to itemId,
                    "display_name" to displayName,
                    "idempotency_key" to idempotencyKey,
                ),
            ),
        )

        val response = executeLookupByIdempotencyKey(idempotencyKey)
        val responseCode = response.code()
        val lookup = response.body()
        val errorMessage = if (responseCode in 400..499) {
            response.extractErrorMessage()
        } else {
            null
        }

        Timber.tag("WorkManager").i(
            UploadLog.message(
                category = CATEGORY_HTTP_RESPONSE,
                action = "upload_reconcile_response",
                uri = uri,
                details = buildList {
                    add("queue_item_id" to itemId)
                    add("display_name" to displayName)
                    add("http_code" to responseCode)
                    lookup?.uploadId?.takeIf { it.isNotBlank() }?.let { add("upload_id" to it) }
                    lookup?.status?.takeIf { it.isNotBlank() }?.let { add("status" to it) }
                    errorMessage?.let { add("error_message" to it) }
                }.toTypedArray(),
            ),
        )

        val resolvedUploadId = lookup?.uploadId
        if (resolvedUploadId.isNullOrBlank()) {
            return null
        }

        return UploadResolution(resolvedUploadId, lookup.status)
    }

    private suspend fun recordError(
        displayName: String,
        errorKind: UploadErrorKind,
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
        errorKind: UploadErrorKind,
        httpCode: Int? = null,
        uri: Uri? = null,
        throwable: Throwable? = null,
        headers: Headers? = null,
    ): Result {
        Timber.tag("WorkManager").w(
            throwable,
            UploadLog.message(
                category = CATEGORY_UPLOAD_RETRY,
                action = "upload_retry",
                uri = uri,
                details = buildList {
                    currentItemId?.let { add("queue_item_id" to it) }
                    add("display_name" to displayName)
                    add("error_kind" to errorKind)
                    httpCode?.let { add("http_code" to it) }
                    throwable?.message?.let { add("message" to it) }
                    headers?.get(RETRY_AFTER_HEADER)?.let { add("retry_after" to it) }
                }.toTypedArray(),
            )
        )
        recordError(displayName, errorKind, httpCode)
        maybeDelayForRetryAfter(headers)
        return Result.retry()
    }

    private suspend fun recordAcceptance(
        itemId: Long,
        displayName: String,
        uri: Uri,
        uploadId: String?,
    ) {
        try {
            uploadQueueRepository.markAccepted(
                id = itemId,
                uploadId = uploadId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logGuardError(
                action = "mark_accepted_failed",
                uri = uri,
                details = guardDetails(
                    displayName = displayName,
                    additional = buildList {
                        uploadId?.let { add("upload_id" to it) }
                    },
                ),
                throwable = error,
            )
        }
    }

    private suspend fun maybeDelayForRetryAfter(headers: Headers?) {
        val value = headers?.get(RETRY_AFTER_HEADER) ?: return
        val delayMillis = parseRetryAfterMillis(value) ?: return
        if (delayMillis > 0) {
            delay(delayMillis)
        }
    }

    private fun parseRetryAfterMillis(raw: String): Long? {
        raw.trim().toLongOrNull()?.let { seconds ->
            return seconds.coerceAtLeast(0).times(1000)
        }
        return runCatching {
            val targetInstant = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw))
            val diff = targetInstant.toEpochMilli() - System.currentTimeMillis()
            diff.coerceAtLeast(0)
        }.getOrNull()
    }

    private fun logUploadError(
        displayName: String,
        errorKind: UploadErrorKind,
        uri: Uri?,
        throwable: Throwable,
        httpCode: Int? = null,
    ) {
        Timber.tag("WorkManager").e(
            throwable,
            UploadLog.message(
                category = CATEGORY_UPLOAD_ERROR,
                action = "upload_error",
                uri = uri,
                details = buildList {
                    currentItemId?.let { add("queue_item_id" to it) }
                    add("display_name" to displayName)
                    add("error_kind" to errorKind)
                    httpCode?.let { add("http_code" to it) }
                    throwable.message?.takeIf { it.isNotBlank() }?.let { add("message" to it) }
                }.toTypedArray(),
            ),
        )
    }

    private fun logPostNotRetried(
        itemId: Long,
        displayName: String,
        uri: Uri,
        idempotencyKey: String,
        reason: AcceptanceSkipReason,
        throwable: Throwable?,
    ) {
        val details = buildList {
            add("queue_item_id" to itemId)
            add("display_name" to displayName)
            add("idempotency_key" to idempotencyKey)
            add("decision" to "post_not_retried")
            add("reason" to reason.rawValue)
            throwable?.message?.takeIf { it.isNotBlank() }?.let { add("message" to it) }
        }
        Timber.tag("WorkManager").i(
            throwable,
            UploadLog.message(
                category = CATEGORY_UPLOAD_SUCCESS,
                action = "upload_post_not_retried",
                uri = uri,
                details = details.toTypedArray(),
            ),
        )
    }

    private suspend fun failureResult(
        itemId: Long,
        displayName: String,
        uriString: String,
        errorKind: UploadErrorKind,
        httpCode: Int? = null,
        errorMessage: String? = null,
        throwable: Throwable? = null,
    ): Result {
        val parsedUri = runCatching { Uri.parse(uriString) }.getOrNull()
        val resolvedMessage = errorMessage
            ?: throwable?.message?.takeIf { it.isNotBlank() }
        Timber.tag("WorkManager").e(
            throwable,
            UploadLog.message(
                category = CATEGORY_UPLOAD_FAILURE,
                action = "upload_failure",
                uri = parsedUri,
                details = buildList {
                    add("queue_item_id" to itemId)
                    add("display_name" to displayName)
                    add("error_kind" to errorKind)
                    httpCode?.let { add("http_code" to it) }
                    resolvedMessage?.let { add("message" to it) }
                }.toTypedArray(),
            )
        )
        recordError(displayName, errorKind, httpCode)
        try {
            uploadQueueRepository.markFailed(
                id = itemId,
                errorKind = errorKind,
                httpCode = httpCode,
                requeue = false,
                errorMessage = resolvedMessage,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logGuardError(
                action = "mark_failed_failed",
                uri = parsedUri,
                details = guardDetails(
                    displayName = displayName,
                    errorKind = errorKind,
                    httpCode = httpCode,
                ),
                throwable = error,
            )
        }
        return Result.failure(
            buildResultData(
                displayName = displayName,
                uriString = uriString,
                bytesSent = lastProgressSnapshot.bytesSent,
                totalBytes = lastProgressSnapshot.totalBytes,
                errorKind = errorKind,
                httpCode = httpCode,
                errorMessage = resolvedMessage,
            )
        )
    }

    private fun buildResultData(
        displayName: String,
        uriString: String,
        uploadId: String? = null,
        bytesSent: Long? = null,
        totalBytes: Long? = null,
        errorKind: UploadErrorKind? = null,
        httpCode: Int? = null,
        errorMessage: String? = null,
        completionState: String? = null,
    ): Data {
        val builder = Data.Builder()
            .putString(UploadEnqueuer.KEY_URI, uriString)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
        uploadId?.let { builder.putString(UploadEnqueuer.KEY_UPLOAD_ID, it) }
        bytesSent?.let { builder.putLong(UploadEnqueuer.KEY_BYTES_SENT, it) }
        totalBytes?.let { builder.putLong(UploadEnqueuer.KEY_TOTAL_BYTES, it) }
        errorKind?.let { builder.putString(UploadEnqueuer.KEY_ERROR_KIND, it.rawValue) }
        httpCode?.let { builder.putInt(UploadEnqueuer.KEY_HTTP_CODE, it) }
        errorMessage?.let { builder.putString(UploadEnqueuer.KEY_ERROR_MESSAGE, it) }
        completionState?.let { builder.putString(UploadEnqueuer.KEY_COMPLETION_STATE, it) }
        return builder.build()
    }

    private fun Response<*>.extractErrorMessage(): String? {
        val rawBody = runCatching { errorBody()?.string() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            val json = JSONObject(rawBody)
            val message = json.optString("message").takeIf { it.isNotBlank() }
            val error = json.optString("error").takeIf { it.isNotBlank() }
            message ?: error
        }.getOrNull() ?: rawBody
    }

    private fun createForeground(displayName: String, progress: Int): ForegroundInfo {
        return foregroundDelegate.create(displayName, progress, id, UploadForegroundKind.UPLOAD)
    }

    private fun ensureSummaryRunning(displayName: String, uri: Uri) {
        try {
            summaryStarter.ensureRunning()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logGuardError(
                action = "summary_start_failed",
                uri = uri,
                details = guardDetails(displayName = displayName),
                throwable = error,
            )
        }
    }

    private suspend fun enqueuePollWork(
        itemId: Long,
        uploadId: String?,
        uriString: String,
        displayName: String,
        idempotencyKey: String,
        uri: Uri,
    ) {
        if (uploadId.isNullOrBlank()) {
            Timber.tag("WorkManager").i(
                UploadLog.message(
                    category = CATEGORY_WORK_SCHEDULE,
                    action = "upload_poll_skipped",
                    uri = uri,
                    details = arrayOf(
                        "queue_item_id" to itemId,
                        "display_name" to displayName,
                        "reason" to "missing_upload_id",
                    ),
                ),
            )
            return
        }
        val constraints = constraintsProvider.awaitConstraints()
        val uniqueName = UploadEnqueuer.uniqueNameForUri(uri)
        val pollRequestBuilder = OneTimeWorkRequestBuilder<PollStatusWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    UploadEnqueuer.KEY_ITEM_ID to itemId,
                    UploadEnqueuer.KEY_UPLOAD_ID to uploadId,
                    UploadEnqueuer.KEY_URI to uriString,
                    UploadEnqueuer.KEY_DISPLAY_NAME to displayName,
                )
            )
            .addTag(UploadTags.TAG_POLL)
            .addTag(UploadTags.uniqueTag(uniqueName))
            .addTag(UploadTags.uriTag(uriString))
            .addTag(UploadTags.displayNameTag(displayName))
            .addTag(UploadTags.keyTag(idempotencyKey))
            .addTag(UploadTags.kindTag(UploadWorkKind.POLL))
        if (constraintsProvider.shouldUseExpeditedWork()) {
            pollRequestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        val pollRequest = pollRequestBuilder.build()

        val workManager = workManagerProvider.get()
        workManager.enqueueUniqueWork(
            "$uniqueName:poll",
            ExistingWorkPolicy.REPLACE,
            pollRequest,
        )
        Timber.tag("WorkManager").i(
            UploadLog.message(
                category = CATEGORY_WORK_SCHEDULE,
                action = "upload_poll_scheduled",
                uri = uri,
                details = arrayOf(
                    "queue_item_id" to itemId,
                    "display_name" to displayName,
                    "upload_id" to uploadId,
                ),
            )
        )
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_FILE_NAME = "photo.jpg"
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private const val INDETERMINATE_PROGRESS = -1
        private const val CATEGORY_UPLOAD_START = "UPLOAD/START"
        private const val CATEGORY_UPLOAD_PREPARE = "UPLOAD/PREPARE"
        private const val CATEGORY_UPLOAD_PROGRESS = "UPLOAD/PROGRESS"
        private const val CATEGORY_UPLOAD_PROGRESS_STATE = "UPLOAD/PROGRESS_STATE"
        private const val CATEGORY_UPLOAD_SUCCESS = "UPLOAD/SUCCESS"
        private const val CATEGORY_UPLOAD_FAILURE = "UPLOAD/FAILURE"
        private const val CATEGORY_UPLOAD_RETRY = "UPLOAD/RETRY"
        private const val CATEGORY_UPLOAD_ERROR = "UPLOAD/ERROR"
        private const val CATEGORY_UPLOAD_CONTENT = "UPLOAD/CONTENT"
        private const val CATEGORY_HTTP_REQUEST = "HTTP/REQUEST"
        private const val CATEGORY_HTTP_RESPONSE = "HTTP/RESPONSE"
        private const val CATEGORY_WORK_SCHEDULE = "WORK/SCHEDULE"
        private const val CATEGORY_UPLOAD_GUARD = "UPLOAD/GUARD"
        private const val RETRY_AFTER_HEADER = "Retry-After"
    }

    private data class ProgressSnapshot(
        val progress: Int = INDETERMINATE_PROGRESS,
        val bytesSent: Long? = null,
        val totalBytes: Long? = null,
    )

    private data class UploadResolution(
        val uploadId: String,
        val status: String?,
    )

    private enum class AcceptanceSkipReason(val rawValue: String) {
        NETWORK("lookup_failure"),
        MISSING_UPLOAD_ID("missing_upload_id"),
    }

    private fun guardDetails(
        displayName: String? = null,
        progress: Int? = null,
        bytesSent: Long? = null,
        totalBytes: Long? = null,
        errorKind: UploadErrorKind? = null,
        httpCode: Int? = null,
        additional: List<Pair<String, Any?>> = emptyList(),
    ): List<Pair<String, Any?>> {
        val details = mutableListOf<Pair<String, Any?>>()
        val itemId = currentItemId
        if (itemId != null) {
            details += "queue_item_id" to itemId
        }
        displayName?.let { details += "display_name" to it }
        progress?.let { details += "progress" to it }
        bytesSent?.let { details += "bytes_sent" to it }
        totalBytes?.let { details += "total_bytes" to it }
        errorKind?.let { details += "error_kind" to it }
        httpCode?.let { details += "http_code" to it }
        details += additional
        return details
    }

    private fun logGuardError(
        action: String,
        uri: Uri? = null,
        details: List<Pair<String, Any?>>,
        throwable: Throwable,
    ) {
        Timber.tag("WorkManager").e(
            throwable,
            UploadLog.message(
                category = CATEGORY_UPLOAD_GUARD,
                action = action,
                uri = uri,
                details = details.toTypedArray(),
            ),
        )
    }

    private suspend fun resolveContentSize(uri: Uri): Long = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val normalizedUri = resolver.requireOriginalIfNeeded(uri)
        resolver.logUriReadDebug("UploadWorker.size", uri, normalizedUri)
        resolver.openAssetFileDescriptor(normalizedUri, "r")?.use { it.length } ?: -1L
    }

    private suspend fun executeUpload(
        idempotencyKey: String,
        payload: UploadRequestPayload,
        requestBody: RequestBody,
    ): Response<UploadAcceptedDto> = withContext(Dispatchers.IO) {
        uploadApi.upload(
            idempotencyKey = idempotencyKey,
            contentSha256Header = payload.requestSha256Hex,
            hasGpsHeader = payload.gpsState.headerValue,
            exifSourceHeader = payload.exifSource.headerValue,
            body = requestBody,
        )
    }

    private suspend fun executeLookupByIdempotencyKey(
        idempotencyKey: String,
    ): Response<UploadLookupDto> = withContext(Dispatchers.IO) {
        uploadApi.getByIdempotencyKey(idempotencyKey)
    }
}
