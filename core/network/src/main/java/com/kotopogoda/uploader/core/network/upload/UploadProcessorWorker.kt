package com.kotopogoda.uploader.core.network.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.UploadTaskParams
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.UploadTaskResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class UploadProcessorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: UploadQueueRepository,
    private val workManager: WorkManager,
    private val constraintsHelper: UploadConstraintsHelper,
    private val taskRunner: UploadTaskRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Timber.tag("WorkManager").i(UploadLog.message(action = "worker_start"))
        val recovered = repository.recoverStuckProcessing()
        if (recovered > 0) {
            Timber.tag("WorkManager").w(
                UploadLog.message(
                    action = "worker_recovered_processing",
                    state = UploadItemState.QUEUED,
                    "requeued" to recovered,
                )
            )
        }
        val queued = repository.fetchQueued(BATCH_SIZE, recoverStuck = false)
        Timber.tag("WorkManager").i(
            UploadLog.message(
                action = "worker_batch",
                state = UploadItemState.QUEUED,
                "fetched" to queued.size,
            )
        )
        if (queued.isEmpty()) {
            if (repository.hasQueued()) {
                enqueueSelf()
            }
            Timber.tag("WorkManager").i(UploadLog.message(action = "worker_complete", "result" to "no_items"))
            return@withContext Result.success()
        }

        var shouldRetry = false

        for (item in queued) {
            Timber.tag("WorkManager").i(
                UploadLog.message(
                    action = "worker_item_start",
                    itemId = item.id,
                    uri = item.uri,
                    "displayName" to item.displayName,
                    "size" to item.size,
                )
            )
            val markedProcessing = repository.markProcessing(item.id)
            if (!markedProcessing) {
                Timber.tag("WorkManager").i(
                    UploadLog.message(
                        action = "worker_item_skip",
                        itemId = item.id,
                        uri = item.uri,
                        "reason" to "state_changed",
                    )
                )
                continue
            }
            val outcome = try {
                taskRunner.run(
                    UploadTaskParams(
                        uri = item.uri,
                        idempotencyKey = item.idempotencyKey,
                        displayName = item.displayName,
                    )
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                UploadTaskResult.Failure(
                    errorKind = error.toUploadErrorKind(),
                    httpCode = null,
                    retryable = error.isRetryable(),
                )
            }

            val isProcessing = repository.getState(item.id) == UploadItemState.PROCESSING
            when (outcome) {
                is UploadTaskResult.Success -> {
                    if (isProcessing) {
                        repository.markSucceeded(item.id)
                        Timber.tag("WorkManager").i(
                            UploadLog.message(
                                action = "worker_item_success",
                                itemId = item.id,
                                uri = item.uri,
                                state = UploadItemState.SUCCEEDED,
                                "displayName" to item.displayName,
                                "size" to item.size,
                            )
                        )
                    }
                }
                is UploadTaskResult.Failure -> {
                    if (isProcessing) {
                        repository.markFailed(
                            id = item.id,
                            errorKind = outcome.errorKind,
                            httpCode = outcome.httpCode,
                            requeue = outcome.retryable,
                        )
                        Timber.tag("WorkManager").w(
                            UploadLog.message(
                                action = "worker_item_failure",
                                itemId = item.id,
                                uri = item.uri,
                                state = if (outcome.retryable) UploadItemState.QUEUED else UploadItemState.FAILED,
                                "errorKind" to outcome.errorKind,
                                "httpCode" to outcome.httpCode,
                                "retry" to outcome.retryable,
                            )
                        )
                        if (outcome.retryable) {
                            shouldRetry = true
                        }
                    }
                }
            }
        }

        if (repository.hasQueued()) {
            enqueueSelf()
            Timber.tag("WorkManager").i(UploadLog.message(action = "worker_reschedule"))
        }

        val result = if (shouldRetry) {
            Timber.tag("WorkManager").i(UploadLog.message(action = "worker_complete", "result" to "retry"))
            Result.retry()
        } else {
            Timber.tag("WorkManager").i(UploadLog.message(action = "worker_complete", "result" to "success"))
            Result.success()
        }
        result
    }

    private fun enqueueSelf() {
        val request = OneTimeWorkRequestBuilder<UploadProcessorWorker>()
            .setConstraints(constraintsHelper.buildConstraints())
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        Timber.tag("WorkManager").i(UploadLog.message(action = "worker_enqueue"))
    }

    companion object {
        const val WORK_NAME = UPLOAD_PROCESSOR_WORK_NAME
        private const val BATCH_SIZE = 5
    }
}

private fun Throwable.toUploadErrorKind(): UploadErrorKind = when (this) {
    is UnknownHostException -> UploadErrorKind.NETWORK
    is IOException -> UploadErrorKind.IO
    else -> UploadErrorKind.UNEXPECTED
}

private fun Throwable.isRetryable(): Boolean = this is UnknownHostException || this is IOException
