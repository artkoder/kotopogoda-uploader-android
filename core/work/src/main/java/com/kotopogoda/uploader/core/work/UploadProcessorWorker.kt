package com.kotopogoda.uploader.core.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
            repository.markProcessing(item.id)
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

            when (outcome) {
                is UploadTaskResult.Success -> {
                    repository.markSucceeded(item.id)
                }
                is UploadTaskResult.Failure -> {
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
