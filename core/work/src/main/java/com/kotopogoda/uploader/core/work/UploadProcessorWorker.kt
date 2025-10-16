package com.kotopogoda.uploader.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UPLOAD_PROCESSOR_WORK_NAME
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.UploadTaskParams
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.UploadTaskResult
import com.kotopogoda.uploader.core.network.upload.UploadWorkErrorKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        repository.recoverStuckProcessing()
        val queued = repository.fetchQueued(BATCH_SIZE, recoverStuck = false)
        if (queued.isEmpty()) {
            if (repository.hasQueued()) {
                enqueueSelf()
            }
            return@withContext Result.success()
        }

        var shouldRetry = false

        for (item in queued) {
            val markedProcessing = repository.markProcessing(item.id)
            if (!markedProcessing) {
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
                        if (outcome.retryable) {
                            shouldRetry = true
                        }
                    }
                }
            }
        }

        if (repository.hasQueued()) {
            enqueueSelf()
        }

        return@withContext if (shouldRetry) Result.retry() else Result.success()
    }

    private fun enqueueSelf() {
        val request = OneTimeWorkRequestBuilder<UploadProcessorWorker>()
            .setConstraints(constraintsHelper.buildConstraints())
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private fun enqueueUploadWork(item: UploadQueueItem) {
        val uniqueName = uniqueName(item.uri)
        val uploadRequest = createUploadRequest(uniqueName, item)
        val pollRequest = createPollRequest(uniqueName, item)
        workManager.beginUniqueWork(UPLOAD_QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, uploadRequest)
            .then(pollRequest)
            .enqueue()
    }

    private fun createUploadRequest(
        uniqueName: String,
        item: UploadQueueItem,
    ) = OneTimeWorkRequestBuilder<UploadWorker>()
        .setInputData(
            androidx.work.workDataOf(
                UploadEnqueuer.KEY_ITEM_ID to item.id,
                UploadEnqueuer.KEY_URI to item.uri.toString(),
                UploadEnqueuer.KEY_IDEMPOTENCY_KEY to item.idempotencyKey,
                UploadEnqueuer.KEY_DISPLAY_NAME to item.displayName,
            )
        )
        .setConstraints(constraintsHelper.buildConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
        .addTag(UploadTags.TAG_UPLOAD)
        .addTag(UploadTags.kindTag(UploadWorkKind.UPLOAD))
        .addTag(UploadTags.uniqueTag(uniqueName))
        .addTag(UploadTags.uriTag(item.uri.toString()))
        .addTag(UploadTags.displayNameTag(item.displayName))
        .addTag(UploadTags.keyTag(item.idempotencyKey))
        .build()

    private fun createPollRequest(
        uniqueName: String,
        item: UploadQueueItem,
    ) = OneTimeWorkRequestBuilder<PollStatusWorker>()
        .setInputData(
            androidx.work.workDataOf(
                UploadEnqueuer.KEY_ITEM_ID to item.id,
                UploadEnqueuer.KEY_URI to item.uri.toString(),
                UploadEnqueuer.KEY_DISPLAY_NAME to item.displayName,
            )
        )
        .setInputMerger(OverwritingInputMerger::class.java)
        .setConstraints(constraintsHelper.buildConstraints())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, POLL_INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
        .addTag(UploadTags.TAG_POLL)
        .addTag(UploadTags.kindTag(UploadWorkKind.POLL))
        .addTag(UploadTags.uniqueTag(uniqueName))
        .addTag(UploadTags.uriTag(item.uri.toString()))
        .addTag(UploadTags.displayNameTag(item.displayName))
        .addTag(UploadTags.keyTag(item.idempotencyKey))
        .build()

    companion object {
        const val WORK_NAME = UPLOAD_PROCESSOR_WORK_NAME
        private const val BATCH_SIZE = 5
    }
}

private fun Throwable.toUploadErrorKind(): UploadWorkErrorKind = when (this) {
    is UnknownHostException -> UploadWorkErrorKind.NETWORK
    is IOException -> UploadWorkErrorKind.IO
    else -> UploadWorkErrorKind.UNEXPECTED
}

private fun Throwable.isRetryable(): Boolean = this is UnknownHostException || this is IOException
