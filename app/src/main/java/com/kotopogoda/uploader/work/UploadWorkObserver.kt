package com.kotopogoda.uploader.work

import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.network.upload.UploadTags
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class UploadWorkObserver @Inject constructor(
    private val workManager: WorkManager,
) {

    private val started = AtomicBoolean(false)
    private val lastStates = mutableMapOf<UUID, WorkInfo.State>()

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) {
            return
        }
        val query = WorkQuery.Builder
            .fromTags(listOf(UploadTags.TAG_UPLOAD, UploadTags.TAG_POLL))
            .build()
        scope.launch {
            workManager.getWorkInfosFlow(query).collect { infos ->
                handleWorkInfos(infos)
            }
        }
    }

    private fun handleWorkInfos(infos: List<WorkInfo>) {
        val knownIds = infos.map { it.id }.toSet()
        val iterator = lastStates.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in knownIds) {
                iterator.remove()
            }
        }

        infos.forEach { info ->
            val previous = lastStates.put(info.id, info.state)
            logStateChange(info, previous)
        }
    }

    private fun logStateChange(info: WorkInfo, previous: WorkInfo.State?) {
        if (previous == info.state) {
            return
        }

        val metadata = UploadTags.metadataFrom(info)
        val baseDetails = buildList {
            add("work_id" to info.id)
            add("state" to info.state.name)
            previous?.let { add("previous_state" to it.name) }
            add("run_attempts" to info.runAttemptCount)
            metadata.uniqueName?.let { add("unique" to it) }
            metadata.uri?.let { add("uri" to it.toString()) }
            metadata.displayName?.let { add("display_name" to it) }
            metadata.idempotencyKey?.let { add("idempotency_key" to it) }
            add("kind" to metadata.kind.rawValue)
        }.toTypedArray()

        Timber.tag(TAG).i(
            UploadLog.message(
                category = "WORK/STATE_CHANGE",
                action = info.state.name.lowercase(),
                details = baseDetails,
            )
        )

        if (previous == null) {
            Timber.tag(TAG).i(
                UploadLog.message(
                    category = "WORK/ENQUEUE",
                    action = "created",
                    details = baseDetails,
                )
            )
        }

        if (info.state == WorkInfo.State.RUNNING && previous != WorkInfo.State.RUNNING) {
            Timber.tag(TAG).i(
                UploadLog.message(
                    category = "WORK/START",
                    action = "running",
                    details = baseDetails,
                )
            )
        }

        if (info.state.isFinished) {
            Timber.tag(TAG).i(
                UploadLog.message(
                    category = "WORK/DONE",
                    action = info.state.name.lowercase(),
                    details = buildList {
                        addAll(baseDetails)
                        if (info.outputData.keyValueMap.isNotEmpty()) {
                            add("output_keys" to info.outputData.keyValueMap.keys.joinToString(","))
                        }
                    }.toTypedArray(),
                )
            )
        }
    }

    private companion object {
        private const val TAG = "WorkManager"
    }
}
