package com.kotopogoda.uploader.core.network.upload

import androidx.work.WorkInfo
import com.kotopogoda.uploader.core.data.upload.UploadLog

internal data class DrainChainCandidate(
    val info: WorkInfo,
    val stuckSince: Long,
    val checked: Int,
)

internal fun findDrainChainCandidate(
    infos: List<WorkInfo>,
    now: Long,
    progressKey: String,
): DrainChainCandidate? {
    var checked = 0
    var bestInfo: WorkInfo? = null
    var bestStuckSince = Long.MAX_VALUE

    for (info in infos) {
        val stuckSince = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING ->
                info.computeStuckSince(now, progressKey)
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED ->
                info.computeFailureMoment(now, progressKey)
            else -> null
        } ?: continue
        checked += 1
        if (bestInfo == null || stuckSince < bestStuckSince) {
            bestInfo = info
            bestStuckSince = stuckSince
        }
    }

    val candidateInfo = bestInfo ?: return null
    return DrainChainCandidate(
        info = candidateInfo,
        stuckSince = bestStuckSince,
        checked = checked,
    )
}

private fun WorkInfo.computeFailureMoment(now: Long, progressKey: String): Long? {
    val failureAt = outputData.getLong(QueueDrainWorker.FAILURE_AT_KEY, 0L)
        .takeIf { it > 0L && it <= now }
    return failureAt ?: computeStuckSince(now, progressKey)
}

private fun WorkInfo.computeStuckSince(now: Long, progressKey: String): Long? {
    val startedAt = progress.getLong(progressKey, 0L).takeIf { it > 0L && it <= now }
    val nextSchedule = nextScheduleTimeMillis.takeIf { it > 0L && it <= now }
    return sequenceOf(startedAt, nextSchedule)
        .filterNotNull()
        .minOrNull()
}

internal fun drainChainSnapshotMessage(
    infos: List<WorkInfo>,
    source: String,
    progressKey: String,
): String {
    val states = infos.joinToString(separator = ";") { info ->
        "${info.id}:${info.state.name}"
    }.takeIf { it.isNotEmpty() }
    val nextScheduleMin = infos
        .mapNotNull { info -> info.nextScheduleTimeMillis.takeIf { it > 0L } }
        .minOrNull()
    val progressMin = infos
        .mapNotNull { info -> info.progress.getLong(progressKey, 0L).takeIf { it > 0L } }
        .minOrNull()

    val details = buildList {
        add("source" to source)
        add("count" to infos.size)
        states?.let { add("states" to it) }
        nextScheduleMin?.let { add("nextScheduleMin" to it) }
        progressMin?.let { add("progressMin" to it) }
    }.toTypedArray()

    return UploadLog.message(
        category = "QUEUE/SCHEDULE_SNAPSHOT",
        action = "drain_chain",
        details = details,
    )
}
