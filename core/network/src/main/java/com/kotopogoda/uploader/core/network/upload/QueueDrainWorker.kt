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
import com.kotopogoda.uploader.core.data.upload.UploadLog
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
        Timber.tag(LOG_TAG).i(UploadLog.message(action = "drain_worker_start"))
        val updatedBefore = System.currentTimeMillis() - UploadQueueRepository.STUCK_TIMEOUT_MS
        repository.recoverStuckProcessing(updatedBefore)
        val queued = repository.fetchQueued(BATCH_SIZE, recoverStuck = false)
        Timber.tag(LOG_TAG).i(
            UploadLog.message(
                action = "drain_worker_batch",
                details = arrayOf(
                    "fetched" to queued.size,
                ),
            )
        )
        if (queued.isEmpty()) {
            if (repository.hasQueued()) {
                enqueueSelf()
            }
            Timber.tag(LOG_TAG).i(
                UploadLog.message(
                    action = "drain_worker_complete",
                    details = arrayOf(
                        "result" to "no_items",
                    ),
                )
            )
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
                Timber.tag(LOG_TAG).i(
                    UploadLog.message(
                        action = "drain_worker_processing_skip",
                        itemId = item.id,
                        uri = item.uri,
                        details = arrayOf(
                            "reason" to "state_changed",
                        ),
                    )
                )
                continue
            }
            Timber.tag(LOG_TAG).i(
                UploadLog.message(
                    action = "drain_worker_processing_success",
                    itemId = item.id,
                    uri = item.uri,
                    details = arrayOf(
                        "displayName" to item.displayName,
                    ),
                )
            )
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
            Timber.tag(LOG_TAG).i(
                UploadLog.message(
                    action = "drain_worker_enqueue_upload",
                    itemId = item.id,
                    uri = item.uri,
                    details = arrayOf(
                        "uniqueName" to uniqueName,
                    ),
                )
            )
        }

        if (repository.hasQueued()) {
            enqueueSelf()
        }

        Timber.tag(LOG_TAG).i(
            UploadLog.message(
                action = "drain_worker_complete",
                details = arrayOf(
                    "result" to "success",
                ),
            )
        )
        Result.success()
    }

    private fun enqueueSelf() {
        val constraints = constraintsProvider.constraintsState.value ?: run {
            Timber.tag(LOG_TAG).i(
                UploadLog.message(
                    action = "drain_worker_constraints_missing",
                    details = arrayOf(
                        "source" to "worker",
                    ),
                ),
            )
            try {
                constraintsProvider.buildConstraints().also {
                    Timber.tag(LOG_TAG).i(
                        UploadLog.message(
                            action = "drain_worker_constraints_built",
                            details = arrayOf(
                                "source" to "worker",
                            ),
                        ),
                    )
                }
            } catch (error: Throwable) {
                Timber.tag(LOG_TAG).e(
                    error,
                    UploadLog.message(
                        action = "drain_worker_constraints_error",
                        details = arrayOf(
                            "source" to "worker",
                        ),
                    ),
                )
                return
            }
        }
        val builder = OneTimeWorkRequestBuilder<QueueDrainWorker>()
            .setConstraints(constraints)
        val expedited = constraintsProvider.shouldUseExpeditedWork()
        if (expedited) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        val request = builder.build()
        val policy = ExistingWorkPolicy.APPEND_OR_REPLACE
        workManager.enqueueUniqueWork(
            QUEUE_DRAIN_WORK_NAME,
            policy,
            request,
        )
        Timber.tag(LOG_TAG).i(
            UploadLog.message(
                action = "drain_worker_reschedule",
                details = arrayOf(
                    "policy" to policy.name,
                    "expedited" to expedited,
                ),
            ),
        )
    }
    companion object {
        private const val BATCH_SIZE = 5
        private const val LOG_TAG = "WorkManager"

        internal fun resetEnqueuePolicy() {
        }
    }
}
