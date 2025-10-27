package com.kotopogoda.uploader.core.network.upload

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull

import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.api.UploadStatusDto
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.DeleteCompletionState
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.UploadTaskResult
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.UploadTaskResult.Failure
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.UploadTaskResult.Success
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.UploadTaskState
import com.kotopogoda.uploader.core.work.UploadErrorKind

@Singleton
class UploadTaskRunner @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val uploadApi: UploadApi,
    private val summaryStarter: UploadSummaryStarter,
) {

    suspend fun run(params: UploadTaskParams): UploadTaskResult = withContext(Dispatchers.IO) {
        summaryStarter.ensureRunning()
        val uri = params.uri
        try {
            val mediaType = resolveMediaType(uri)
            val payload = prepareUploadRequestPayload(
                resolver = appContext.contentResolver,
                uri = uri,
                displayName = params.displayName,
                mimeType = mediaType.toString(),
                mediaType = mediaType,
                totalBytes = -1L,
                boundarySeed = params.idempotencyKey,
            )
            val requestBody = payload.createRequestBody(null)
            val response = try {
                uploadApi.upload(
                    idempotencyKey = params.idempotencyKey,
                    contentSha256Header = payload.requestSha256Hex,
                    body = requestBody,
                )
            } catch (unknown: UnknownHostException) {
                return@withContext Failure(UploadErrorKind.NETWORK, httpCode = null, retryable = true)
            } catch (io: IOException) {
                return@withContext Failure(UploadErrorKind.NETWORK, httpCode = null, retryable = true)
            }
            when (response.code()) {
                202, 409 -> {
                    val uploadId = response.body()?.uploadId
                    if (uploadId.isNullOrBlank()) {
                        return@withContext Failure(UploadErrorKind.UNEXPECTED, httpCode = null, retryable = true)
                    }
                    return@withContext pollUntilComplete(uploadId, params, payload.fileSize)
                }
                413, 415 -> return@withContext Failure(
                    UploadErrorKind.HTTP,
                    httpCode = response.code(),
                    retryable = false
                )
                429 -> return@withContext Failure(
                    UploadErrorKind.HTTP,
                    httpCode = response.code(),
                    retryable = true
                )
                in 500..599 -> return@withContext Failure(
                    UploadErrorKind.HTTP,
                    httpCode = response.code(),
                    retryable = true
                )
                else -> return@withContext Failure(
                    UploadErrorKind.HTTP,
                    httpCode = response.code(),
                    retryable = false
                )
            }
        } catch (security: RecoverableSecurityException) {
            Failure(UploadErrorKind.IO, httpCode = null, retryable = false)
        } catch (security: SecurityException) {
            Failure(UploadErrorKind.IO, httpCode = null, retryable = false)
        } catch (io: IOException) {
            Failure(UploadErrorKind.IO, httpCode = null, retryable = true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Failure(UploadErrorKind.UNEXPECTED, httpCode = null, retryable = false)
        }
    }

    private suspend fun pollUntilComplete(
        uploadId: String,
        params: UploadTaskParams,
        payloadSize: Long,
    ): UploadTaskResult {
        while (true) {
            val response = try {
                uploadApi.getStatus(uploadId)
            } catch (unknown: UnknownHostException) {
                delay(DEFAULT_RETRY_DELAY_MILLIS)
                continue
            } catch (io: IOException) {
                delay(DEFAULT_RETRY_DELAY_MILLIS)
                continue
            }
            when (response.code()) {
                200 -> {
                    val body = response.body()
                    if (body == null) {
                        delay(DEFAULT_RETRY_DELAY_MILLIS)
                        continue
                    }
                    when (resolveRemoteState(body)) {
                        UploadTaskState.QUEUED, UploadTaskState.PROCESSING -> {
                            val delayed = maybeDelayForRetryAfter(response.headers())
                            if (!delayed) {
                                delay(DEFAULT_RETRY_DELAY_MILLIS)
                            }
                            continue
                        }
                        UploadTaskState.DONE -> {
                            val completionState = deleteDocument(params.uri)
                            return Success(
                                completionState = completionState,
                                bytesSent = payloadSize,
                                totalBytes = payloadSize.takeIf { it > 0 }
                            )
                        }
                        UploadTaskState.FAILED -> {
                            return Failure(UploadErrorKind.REMOTE_FAILURE, httpCode = null, retryable = false)
                        }
                    }
                }
                404 -> return Failure(UploadErrorKind.HTTP, httpCode = response.code(), retryable = false)
                429 -> {
                    val delayed = maybeDelayForRetryAfter(response.headers())
                    if (!delayed) {
                        delay(DEFAULT_RETRY_DELAY_MILLIS)
                    }
                    continue
                }
                in 500..599 -> {
                    val delayed = maybeDelayForRetryAfter(response.headers())
                    if (!delayed) {
                        delay(DEFAULT_RETRY_DELAY_MILLIS)
                    }
                    continue
                }
                else -> return Failure(UploadErrorKind.HTTP, httpCode = response.code(), retryable = false)
            }
        }
    }

    private fun resolveRemoteState(dto: UploadStatusDto): UploadTaskState {
        val normalized = dto.status?.lowercase(Locale.US)
        return when (normalized) {
            "queued" -> UploadTaskState.QUEUED
            "processing" -> UploadTaskState.PROCESSING
            "done" -> UploadTaskState.DONE
            "failed" -> UploadTaskState.FAILED
            else -> when {
                dto.error?.isNotBlank() == true -> UploadTaskState.FAILED
                dto.processed == true -> UploadTaskState.DONE
                else -> UploadTaskState.PROCESSING
            }
        }
    }

    private suspend fun maybeDelayForRetryAfter(headers: Headers): Boolean {
        val value = headers[RETRY_AFTER_HEADER] ?: return false
        val delayMillis = parseRetryAfterMillis(value) ?: return false
        if (delayMillis > 0) {
            delay(delayMillis)
            return true
        }
        return false
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

    private fun resolveMediaType(uri: Uri): MediaType {
        val mimeType = appContext.contentResolver.getType(uri)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MIME_TYPE
        return mimeType.toMediaTypeOrNull() ?: DEFAULT_MIME_TYPE.toMediaType()
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

    private fun isMediaStoreUri(uri: Uri): Boolean {
        return uri.authority == MediaStore.AUTHORITY
    }

    data class UploadTaskParams(
        val uri: Uri,
        val idempotencyKey: String,
        val displayName: String,
    )

    sealed class UploadTaskResult {
        data class Success(
            val completionState: DeleteCompletionState,
            val bytesSent: Long?,
            val totalBytes: Long?,
        ) : UploadTaskResult()

        data class Failure(
            val errorKind: UploadErrorKind,
            val httpCode: Int?,
            val retryable: Boolean,
        ) : UploadTaskResult()
    }

    enum class DeleteCompletionState {
        DELETED,
        AWAITING_MANUAL_DELETE,
        UNKNOWN,
    }

    private enum class UploadTaskState {
        QUEUED,
        PROCESSING,
        DONE,
        FAILED,
    }

    companion object {
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
        private const val DEFAULT_RETRY_DELAY_MILLIS = 30_000L
        private const val RETRY_AFTER_HEADER = "Retry-After"
    }
}

