package com.kotopogoda.uploader.core.network.upload

import androidx.work.WorkInfo

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
        if (info.state != WorkInfo.State.ENQUEUED && info.state != WorkInfo.State.RUNNING) {
            continue
        }
        checked += 1
        val stuckSince = info.computeStuckSince(now, progressKey) ?: continue
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

private fun WorkInfo.computeStuckSince(now: Long, progressKey: String): Long? {
    val startedAt = progress.getLong(progressKey, 0L).takeIf { it > 0L && it <= now }
    val nextSchedule = nextScheduleTimeMillis.takeIf { it > 0L && it <= now }
    return sequenceOf(startedAt, nextSchedule)
        .filterNotNull()
        .minOrNull()
}
