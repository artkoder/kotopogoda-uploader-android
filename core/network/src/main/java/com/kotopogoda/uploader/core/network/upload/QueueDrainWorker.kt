package com.kotopogoda.uploader.core.network.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.work.UploadWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltWorker
class QueueDrainWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: UploadQueueRepository,
    private val workManager: WorkManager,
    private val constraintsProvider: UploadConstraintsProvider,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val updatedBefore = System.currentTimeMillis() - UploadQueueRepository.STUCK_TIMEOUT_MS
        repository.recoverStuckProcessing(updatedBefore)
        val queued = repository.fetchQueued(BATCH_SIZE, recoverStuck = false)
        if (queued.isEmpty()) {
            if (repository.hasQueued()) {
                enqueueSelf()
            }
            return@withContext Result.success()
        }

        val constraints = constraintsProvider.awaitConstraints()
        if (constraints == null) {
            Timber.tag("WorkManager").w("Upload constraints not available yet, retrying queue drain")
            return@withContext Result.retry()
        }

        for (item in queued) {
            val markedProcessing = repository.markProcessing(item.id)
            if (!markedProcessing) {
                continue
            }
            val uniqueName = UploadEnqueuer.uniqueNameForUri(item.uri)
            val requestBuilder = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        UploadEnqueuer.KEY_ITEM_ID to item.id,
                        UploadEnqueuer.KEY_URI to item.uri.toString(),
                        UploadEnqueuer.KEY_IDEMPOTENCY_KEY to item.idempotencyKey,
                        UploadEnqueuer.KEY_DISPLAY_NAME to item.displayName,
                    )
                )
                .addTag(UploadTags.TAG_UPLOAD)
                .addTag(UploadTags.uniqueTag(uniqueName))
                .addTag(UploadTags.uriTag(item.uri.toString()))
                .addTag(UploadTags.displayNameTag(item.displayName))
                .addTag(UploadTags.keyTag(item.idempotencyKey))
                .addTag(UploadTags.kindTag(UploadWorkKind.UPLOAD))
            if (constraintsProvider.shouldUseExpeditedWork()) {
                requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val request = requestBuilder.build()
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
        }

        if (repository.hasQueued()) {
            enqueueSelf()
        }

        Result.success()
    }

    private fun enqueueSelf() {
        val wifiOnly = constraintsProvider.wifiOnlyUploadsState.value ?: return
        val constraints = constraintsProvider.constraintsState.value ?: return
        val builder = OneTimeWorkRequestBuilder<QueueDrainWorker>()
            .setConstraints(constraints)
        if (constraintsProvider.shouldUseExpeditedWork()) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        val request = builder.build()
        val policy = if (lastEnqueuedWifiOnly == null || lastEnqueuedWifiOnly != wifiOnly) {
            ExistingWorkPolicy.REPLACE
        } else {
            ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        lastEnqueuedWifiOnly = wifiOnly
        workManager.enqueueUniqueWork(
            QUEUE_DRAIN_WORK_NAME,
            policy,
            request,
        )
    }

    companion object {
        private const val BATCH_SIZE = 5
        @Volatile
        private var lastEnqueuedWifiOnly: Boolean? = null

        internal fun resetEnqueuePolicy() {
            lastEnqueuedWifiOnly = null
        }
    }
}
