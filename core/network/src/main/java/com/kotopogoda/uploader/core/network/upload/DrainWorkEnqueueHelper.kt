package com.kotopogoda.uploader.core.network.upload

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.data.upload.UploadLog
import timber.log.Timber

private typealias OperationFailure = Operation.State.FAILURE

internal fun enqueueDrainWorkWithResult(
    workManager: WorkManager,
    name: String,
    policy: ExistingWorkPolicy,
    source: String,
    initialExpedited: Boolean,
    logTag: String,
    buildRequest: (Boolean) -> OneTimeWorkRequest,
): OneTimeWorkRequest {
    var useExpedited = initialExpedited

    attempts@ while (true) {
        Timber.tag(logTag).i(
            UploadLog.message(
                action = "drain_worker_enqueue_mode",
                details = arrayOf(
                    "source" to source,
                    "mode" to if (useExpedited) "expedited" else "non_expedited",
                ),
            ),
        )

        val request = buildRequest(useExpedited)
        Timber.tag(logTag).i(
            UploadLog.message(
                action = "drain_worker_enqueue",
                details = arrayOf(
                    "source" to source,
                    "requestId" to request.id,
                    "policy" to policy.name,
                    "tags" to request.tags.joinToString(separator = ";"),
                ),
            ),
        )

        val operation = workManager.enqueueUniqueWork(name, policy, request)
        logDrainChainState(workManager, name, source, logTag)

        val state = try {
            operation.result.get()
        } catch (error: Throwable) {
            Timber.tag(logTag).w(
                error,
                UploadLog.message(
                    action = "drain_worker_enqueue_failed",
                    details = arrayOf(
                        "source" to source,
                        "reason" to (error.message ?: error::class.java.simpleName),
                    ),
                ),
            )
            if (useExpedited) {
                Timber.tag(logTag).i(
                    UploadLog.message(
                        action = "drain_worker_enqueue_retry",
                        details = arrayOf(
                            "source" to source,
                            "mode" to "non_expedited",
                        ),
                    ),
                )
                useExpedited = false
                continue@attempts
            }
            return request
        }

        val failureClass = Operation.State.FAILURE::class.java
        if (!failureClass.isInstance(state)) {
            return request
        }

        val failure = failureClass.cast(state) as OperationFailure
        val failureThrowable: Throwable? = failure.throwable
        Timber.tag(logTag).w(
            UploadLog.message(
                action = "drain_worker_enqueue_failed",
                details = arrayOf(
                    "source" to source,
                    "reason" to (failureThrowable?.message ?: failure::class.java.simpleName),
                ),
            ),
        )

        if (!useExpedited) {
            return request
        }

        Timber.tag(logTag).i(
            UploadLog.message(
                action = "drain_worker_enqueue_retry",
                details = arrayOf(
                    "source" to source,
                    "mode" to "non_expedited",
                ),
            ),
        )

        useExpedited = false
        continue@attempts
    }
}

private fun logDrainChainState(
    workManager: WorkManager,
    name: String,
    source: String,
    logTag: String,
) {
    val infos = runCatching {
        workManager.getWorkInfosForUniqueWork(name).get()
    }.getOrElse { error ->
        Timber.tag(logTag).w(
            error,
            UploadLog.message(
                action = "drain_worker_chain_state_error",
                details = arrayOf(
                    "source" to source,
                ),
            ),
        )
        return
    }

    infos.forEachIndexed { index, info ->
        Timber.tag(logTag).i(
            UploadLog.message(
                action = "drain_worker_chain_state",
                details = arrayOf(
                    "source" to source,
                    "index" to index,
                    "workId" to info.id,
                    "state" to info.state.name,
                    "nextSchedule" to info.nextScheduleTimeMillis,
                ),
            ),
        )
    }
}
