package com.kotopogoda.uploader.feature.queue

import android.net.Uri
import androidx.annotation.StringRes
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.feature.queue.R
import java.time.Clock
import java.util.Locale
import javax.inject.Inject

class QueueWorkInfoMapper @Inject constructor(
    private val clock: Clock,
) {

    fun map(info: WorkInfo): QueueItemWorkInfo? {
        val metadata = UploadTags.metadataFrom(info)

        val progressData = info.progress
        val progressPercent = progressData.getIntOrNull(UploadEnqueuer.KEY_PROGRESS)
            ?.takeIf { it >= 0 }
        val bytesSent = progressData.getLongOrNull(UploadEnqueuer.KEY_BYTES_SENT)
        val totalBytes = progressData.getLongOrNull(UploadEnqueuer.KEY_TOTAL_BYTES)

        val waitingReasons = buildWaitingReasons(info, metadata.kind)

        return QueueItemWorkInfo(
            uniqueName = metadata.uniqueName,
            uri = metadata.uri,
            kind = metadata.kind,
            state = info.state,
            statusResId = statusFor(metadata.kind, info),
            progressPercent = progressPercent,
            bytesSent = bytesSent,
            totalBytes = totalBytes,
            waitingReasons = waitingReasons,
            isActiveTransfer = metadata.kind == UploadWorkKind.UPLOAD && info.state == WorkInfo.State.RUNNING,
        )
    }

    private fun statusFor(kind: UploadWorkKind, info: WorkInfo): Int {
        return when (kind) {
            UploadWorkKind.UPLOAD -> statusForUpload(info.state)
            UploadWorkKind.POLL -> statusForPoll(info)
            UploadWorkKind.DRAIN -> statusForUpload(info.state)
        }
    }

    private fun statusForUpload(state: WorkInfo.State): Int {
        return when (state) {
            WorkInfo.State.ENQUEUED -> R.string.queue_status_enqueued
            WorkInfo.State.RUNNING -> R.string.queue_status_running
            WorkInfo.State.SUCCEEDED -> R.string.queue_status_succeeded
            WorkInfo.State.FAILED -> R.string.queue_status_failed
            WorkInfo.State.BLOCKED -> R.string.queue_status_blocked
            WorkInfo.State.CANCELLED -> R.string.queue_status_cancelled
        }
    }

    private fun statusForPoll(info: WorkInfo): Int {
        val completionState = completionState(info)
        return when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> R.string.queue_status_poll_waiting
            WorkInfo.State.RUNNING -> completionState?.let(::statusForPollCompletion)
                ?: R.string.queue_status_poll_waiting
            WorkInfo.State.SUCCEEDED -> completionState?.let(::statusForPollCompletion)
                ?: R.string.queue_status_poll_succeeded
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> R.string.queue_status_poll_failed
        }
    }

    private fun statusForPollCompletion(completionState: String): Int {
        return when (completionState) {
            UploadEnqueuer.STATE_UPLOADED_DELETED -> R.string.queue_status_poll_succeeded
            UploadEnqueuer.STATE_UPLOADED_AWAITING_DELETE -> R.string.queue_status_poll_manual_delete
            UploadEnqueuer.STATE_UPLOAD_COMPLETED_UNKNOWN -> R.string.queue_status_poll_waiting
            else -> R.string.queue_status_poll_waiting
        }
    }

    private fun completionState(info: WorkInfo): String? {
        return info.progress.getString(UploadEnqueuer.KEY_COMPLETION_STATE)
            ?: info.outputData.getString(UploadEnqueuer.KEY_COMPLETION_STATE)
    }

    private fun buildWaitingReasons(info: WorkInfo, kind: UploadWorkKind): List<QueueItemWaitingReason> {
        val reasons = mutableListOf<QueueItemWaitingReason>()
        if (!(kind == UploadWorkKind.POLL && info.state == WorkInfo.State.RUNNING)) {
            reasons += networkReasons(info.constraints)
        }
        nextRetryReason(info)?.let(reasons::add)
        return reasons
    }

    private fun networkReasons(constraints: Constraints): List<QueueItemWaitingReason> {
        val requiredNetworkType = constraints.requiredNetworkType
        val resId = when (requiredNetworkType) {
            NetworkType.UNMETERED -> R.string.queue_network_unmetered
            NetworkType.NOT_ROAMING -> R.string.queue_network_not_roaming
            NetworkType.CONNECTED -> R.string.queue_network_connected
            NetworkType.METERED -> R.string.queue_network_metered
            NetworkType.TEMPORARILY_UNMETERED -> R.string.queue_network_unmetered
            NetworkType.NOT_REQUIRED -> null
        }
        return resId?.let { listOf(QueueItemWaitingReason(it)) } ?: emptyList()
    }

    private fun nextRetryReason(info: WorkInfo): QueueItemWaitingReason? {
        val nextTime = info.nextScheduleTimeMillis
        if (nextTime <= 0L) {
            return null
        }
        val now = clock.millis()
        val delta = nextTime - now
        if (delta <= 0L) {
            return null
        }
        val seconds = (delta / 1_000L).coerceAtLeast(0L)
        val formatted = formatElapsedTime(seconds)
        return QueueItemWaitingReason(
            messageResId = R.string.queue_retry_in,
            formatArgs = listOf(formatted),
        )
    }

    private fun formatElapsedTime(seconds: Long): String {
        val totalSeconds = seconds.coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val remainingSeconds = totalSeconds % 60L
        val locale = Locale.getDefault()
        return if (hours > 0L) {
            String.format(locale, "%02d:%02d:%02d", hours, minutes, remainingSeconds)
        } else {
            String.format(locale, "%02d:%02d", minutes, remainingSeconds)
        }
    }
}

data class QueueItemWorkInfo(
    val uniqueName: String?,
    val uri: Uri?,
    val kind: UploadWorkKind,
    val state: WorkInfo.State,
    @StringRes val statusResId: Int,
    val progressPercent: Int?,
    val bytesSent: Long?,
    val totalBytes: Long?,
    val waitingReasons: List<QueueItemWaitingReason>,
    val isActiveTransfer: Boolean,
)

data class QueueItemWaitingReason(
    @StringRes val messageResId: Int,
    val formatArgs: List<Any> = emptyList(),
)

private fun Data.getIntOrNull(key: String): Int? {
    val value = keyValueMap[key] ?: return null
    return when (value) {
        is Int -> value
        is Number -> value.toInt()
        else -> null
    }
}

private fun Data.getLongOrNull(key: String): Long? {
    val value = keyValueMap[key] ?: return null
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        else -> null
    }
}
