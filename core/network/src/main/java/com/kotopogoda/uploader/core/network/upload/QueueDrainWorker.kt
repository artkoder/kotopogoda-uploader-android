package com.kotopogoda.uploader.core.network.upload

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
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
import kotlinx.coroutines.CancellationException
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

    init {
        Timber.tag(LOG_TAG).i(
            UploadLog.message(
                action = "drain_worker_init",
                details = arrayOf(
                    "id" to params.id,
                    "attempt" to params.runAttemptCount,
                    "tags" to params.tags.joinToString(),
                ),
            ),
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val workManager = workManagerProvider.get()
            Timber.tag(LOG_TAG).i(
                UploadLog.message(
                    action = "drain_worker_start",
                    details = arrayOf(
                        "id" to id.toString(),
                    ),
                )
            )
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

            val constraints = ensureConstraints(source = "worker")

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
        } catch (error: Throwable) {
            handleWorkError(error)
        }
    }

    private fun handleWorkError(error: Throwable): Result {
        if (error is CancellationException) {
            throw error
        }
        val willRetry = runAttemptCount < MAX_ATTEMPTS_BEFORE_FAILURE
        Timber.tag(LOG_TAG).e(
            error,
            UploadLog.message(
                action = "drain_worker_error",
                details = arrayOf(
                    "id" to id.toString(),
                    "attempt" to runAttemptCount,
                    "willRetry" to willRetry,
                ),
            ),
        )
        return if (willRetry) {
            Result.retry()
        } else {
            val failureMessage = error.message ?: error::class.qualifiedName ?: "Unknown error"
            Result.failure(
                workDataOf(
                    FAILURE_MESSAGE_KEY to failureMessage,
                    FAILURE_AT_KEY to System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun enqueueSelf() {
        val workManager = workManagerProvider.get()
        maybeResetStuckDrainChain(workManager, source = "worker")
        val builder = OneTimeWorkRequestBuilder<QueueDrainWorker>()
            .setConstraints(DRAIN_WORK_CONSTRAINTS)
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

    private suspend fun ensureConstraints(source: String): Constraints {
        val hadConstraints = constraintsProvider.constraintsState.value != null
        if (!hadConstraints) {
            Timber.tag(LOG_TAG).i(
                UploadLog.message(
                    action = "drain_worker_constraints_missing",
                    details = arrayOf(
                        "source" to source,
                    ),
                ),
            )
        }

        return constraintsProvider.awaitConstraints().also {
            if (!hadConstraints) {
                Timber.tag(LOG_TAG).i(
                    UploadLog.message(
                        action = "drain_worker_constraints_built",
                        details = arrayOf(
                            "source" to source,
                        ),
                    ),
                )
            }
        }
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

        Timber.tag(LOG_TAG).i(
            drainChainSnapshotMessage(
                infos = infos,
                source = source,
                progressKey = PROGRESS_KEY_STARTED_AT,
            ),
        )

        val now = System.currentTimeMillis()
        val candidate = findDrainChainCandidate(
            infos = infos,
            now = now,
            progressKey = PROGRESS_KEY_STARTED_AT,
        ) ?: return

        val threshold = now - UploadQueueRepository.STUCK_TIMEOUT_MS
        val isFailed = candidate.info.state == WorkInfo.State.FAILED
        if (!isFailed && candidate.stuckSince > threshold) {
            return
        }

        if (isFailed) {
            val failureMessage = candidate.info.outputData.getString(FAILURE_MESSAGE_KEY)
            Timber.tag(LOG_TAG).w(
                UploadLog.message(
                    action = "drain_worker_chain_failed",
                    details = buildList {
                        add("source" to source)
                        add("workId" to candidate.info.id)
                        add("state" to candidate.info.state.name)
                        add("since" to candidate.stuckSince)
                        add("now" to now)
                        add("checked" to candidate.checked)
                        failureMessage?.let { add("failureMessage" to it) }
                    }.toTypedArray(),
                ),
            )
        } else {
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
        }

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
        internal const val FAILURE_MESSAGE_KEY = "QueueDrainWorkerFailureMessage"
        internal const val FAILURE_AT_KEY = "QueueDrainWorkerFailureAt"
        internal const val MAX_ATTEMPTS_BEFORE_FAILURE = 3
        internal const val PROGRESS_KEY_STARTED_AT = "drainWorkerStartedAt"
        private val DRAIN_WORK_CONSTRAINTS: Constraints = Constraints.NONE

        internal fun resetEnqueuePolicy() {
        }
    }
}
