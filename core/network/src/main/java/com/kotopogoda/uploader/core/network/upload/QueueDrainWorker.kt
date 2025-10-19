package com.kotopogoda.uploader.core.network.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
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
import javax.inject.Provider

@HiltWorker
class QueueDrainWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: UploadQueueRepository,
    private val workManagerProvider: Provider<WorkManager>,
    private val constraintsProvider: UploadConstraintsProvider,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val workManager = workManagerProvider.get()
        Timber.tag(LOG_TAG).i(UploadLog.message(action = "drain_worker_start"))
        setProgress(workDataOf(PROGRESS_KEY_STARTED_AT to System.currentTimeMillis()))
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

    private suspend fun enqueueSelf() {
        val workManager = workManagerProvider.get()
        maybeResetStuckDrainChain(workManager, source = "worker")
        val constraints = try {
            constraintsProvider.awaitConstraints()
                ?: run {
                    Timber.tag(LOG_TAG).i(
                        UploadLog.message(
                            action = "drain_worker_constraints_missing",
                            details = arrayOf(
                                "source" to "worker",
                            ),
                        ),
                    )
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
        val builder = OneTimeWorkRequestBuilder<QueueDrainWorker>()
            .setConstraints(constraints)
        // Дренер запускается как обычная задача, так как он не поднимает foreground-service.
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
                    "requestId" to request.id,
                ),
            ),
        )
    }

    private suspend fun maybeResetStuckDrainChain(
        workManager: WorkManager,
        source: String,
    ) {
        val infos = try {
            workManager.getWorkInfosForUniqueWork(QUEUE_DRAIN_WORK_NAME).get()
        } catch (error: Throwable) {
            Timber.tag(LOG_TAG).w(
                error,
                UploadLog.message(
                    action = "drain_worker_chain_inspect_error",
                    details = arrayOf(
                        "source" to source,
                    ),
                ),
            )
            return
        }

        val now = System.currentTimeMillis()
        val candidate = findDrainChainCandidate(
            infos = infos,
            now = now,
            progressKey = PROGRESS_KEY_STARTED_AT,
        ) ?: return

        val threshold = now - UploadQueueRepository.STUCK_TIMEOUT_MS
        if (candidate.stuckSince > threshold) {
            return
        }

        Timber.tag(LOG_TAG).w(
            UploadLog.message(
                action = "drain_worker_chain_stuck",
                details = arrayOf(
                    "source" to source,
                    "workId" to candidate.info.id,
                    "state" to candidate.info.state.name,
                    "since" to candidate.stuckSince,
                    "now" to now,
                    "nextSchedule" to candidate.info.nextScheduleTimeMillis,
                    "startedAt" to candidate.info.progress.getLong(
                        PROGRESS_KEY_STARTED_AT,
                        0L,
                    ),
                    "checked" to candidate.checked,
                ),
            ),
        )

        workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME)
        Timber.tag(LOG_TAG).i(
            UploadLog.message(
                action = "drain_worker_chain_cancel",
                details = arrayOf(
                    "source" to source,
                    "checked" to candidate.checked,
                ),
            ),
        )

        val requeued = try {
            repository.recoverStuckProcessing(threshold)
        } catch (error: Throwable) {
            Timber.tag(LOG_TAG).e(
                error,
                UploadLog.message(
                    action = "drain_worker_chain_requeue_error",
                    details = arrayOf(
                        "source" to source,
                    ),
                ),
            )
            return
        }

        Timber.tag(LOG_TAG).i(
            UploadLog.message(
                action = "drain_worker_chain_requeue",
                details = arrayOf(
                    "source" to source,
                    "requeued" to requeued,
                    "checked" to candidate.checked,
                ),
            ),
        )
    }
    companion object {
        private const val BATCH_SIZE = 5
        private const val LOG_TAG = "WorkManager"
        internal const val PROGRESS_KEY_STARTED_AT = "drainWorkerStartedAt"

        internal fun resetEnqueuePolicy() {
        }
    }
}
