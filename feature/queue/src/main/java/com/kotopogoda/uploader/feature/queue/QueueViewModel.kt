package com.kotopogoda.uploader.feature.queue

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkMetadata
import com.kotopogoda.uploader.core.network.upload.UploadWorkErrorKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import com.kotopogoda.uploader.feature.queue.R

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val uploadEnqueuer: UploadEnqueuer,
    private val summaryStarter: UploadSummaryStarter,
) : ViewModel() {

    init {
        summaryStarter.ensureRunning()
    }

    val uiState: StateFlow<QueueUiState> = uploadEnqueuer.getAllUploadsFlow()
        .map { infos ->
            val items = infos.map { it.toQueueItemUiModel() }
            QueueUiState(items = items)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = QueueUiState()
        )

    fun onCancel(item: QueueItemUiModel) {
        val metadata = item.metadata
        val uniqueName = metadata.uniqueName
        if (uniqueName != null) {
            uploadEnqueuer.cancel(uniqueName)
        } else {
            metadata.uri?.let(uploadEnqueuer::cancel)
        }
    }

    fun onRetry(item: QueueItemUiModel) {
        if (item.canRetry) {
            uploadEnqueuer.retry(item.metadata)
        }
    }

    fun ensureSummaryRunning() {
        summaryStarter.ensureRunning()
    }

data class QueueUiState(
    val items: List<QueueItemUiModel> = emptyList()
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

data class QueueItemUiModel(
    val id: UUID,
    val title: String,
    val progress: Int,
    val state: WorkInfo.State,
    val metadata: UploadWorkMetadata,
    val canCancel: Boolean,
    val canRetry: Boolean,
    @StringRes val statusResId: Int,
    val highlightWarning: Boolean,
    val bytesSent: Long?,
    val totalBytes: Long?,
    val errorKind: UploadWorkErrorKind?,
    val errorHttpCode: Int?,
) {
    val isIndeterminate: Boolean get() = progress < 0
}

internal fun WorkInfo.toQueueItemUiModel(): QueueItemUiModel {
    val metadata = UploadTags.metadataFrom(this)
    val metadataUri = metadata.uri
    val metadataDisplayName = metadata.displayName
    val metadataUniqueName = metadata.uniqueName
    val metadataIdempotencyKey = metadata.idempotencyKey

    val progressValue = progress.getInt(UploadEnqueuer.KEY_PROGRESS, DEFAULT_PROGRESS_VALUE)
    val normalizedProgress = if (progressValue >= 0) progressValue.coerceIn(0, 100) else DEFAULT_PROGRESS_VALUE
    val progressName = progress.getString(UploadEnqueuer.KEY_DISPLAY_NAME)
    val displayName = progressName?.takeIf { it.isNotBlank() }
        ?: metadataDisplayName?.takeIf { it.isNotBlank() }
        ?: metadataUri?.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: DEFAULT_TITLE

    val progressMap = progress.keyValueMap
    val outputMap = outputData.keyValueMap

    val progressBytesSent = progressMap[UploadEnqueuer.KEY_BYTES_SENT] as? Long
    val progressTotalBytes = progressMap[UploadEnqueuer.KEY_TOTAL_BYTES] as? Long
    val outputBytesSent = outputMap[UploadEnqueuer.KEY_BYTES_SENT] as? Long
    val outputTotalBytes = outputMap[UploadEnqueuer.KEY_TOTAL_BYTES] as? Long
    val bytesSent = progressBytesSent ?: outputBytesSent
    val totalBytes = progressTotalBytes ?: outputTotalBytes

    val progressErrorKindRaw = progress.getString(UploadEnqueuer.KEY_ERROR_KIND)
    val outputErrorKindRaw = outputData.getString(UploadEnqueuer.KEY_ERROR_KIND)
    val errorKind = UploadWorkErrorKind.fromRawValue(progressErrorKindRaw ?: outputErrorKindRaw)

    val progressHttpCode = progressMap[UploadEnqueuer.KEY_HTTP_CODE] as? Int
    val outputHttpCode = outputMap[UploadEnqueuer.KEY_HTTP_CODE] as? Int
    val errorHttpCode = progressHttpCode ?: outputHttpCode

    val canCancel = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
    val canRetry = state == WorkInfo.State.FAILED &&
        metadataUniqueName != null &&
        metadataUri != null &&
        metadataIdempotencyKey != null &&
        metadata.kind == UploadWorkKind.UPLOAD
    val deleted = outputMap[UploadEnqueuer.KEY_DELETED] as? Boolean
    val statusResId = statusResId(state, metadata.kind, deleted)
    val highlightWarning = metadata.kind == UploadWorkKind.POLL &&
        state == WorkInfo.State.SUCCEEDED && deleted == false

    return QueueItemUiModel(
        id = id,
        title = displayName,
        progress = normalizedProgress,
        state = state,
        metadata = metadata,
        canCancel = canCancel,
        canRetry = canRetry,
        statusResId = statusResId,
        highlightWarning = highlightWarning,
        bytesSent = bytesSent,
        totalBytes = totalBytes,
        errorKind = errorKind,
        errorHttpCode = errorHttpCode
    )
}

@StringRes
private fun statusResId(
    state: WorkInfo.State,
    kind: UploadWorkKind,
    deleted: Boolean?,
): Int {
    return when (kind) {
        UploadWorkKind.UPLOAD -> when (state) {
            WorkInfo.State.ENQUEUED -> R.string.queue_status_enqueued
            WorkInfo.State.RUNNING -> R.string.queue_status_running
            WorkInfo.State.SUCCEEDED -> R.string.queue_status_succeeded
            WorkInfo.State.FAILED -> R.string.queue_status_failed
            WorkInfo.State.CANCELLED -> R.string.queue_status_cancelled
            WorkInfo.State.BLOCKED -> R.string.queue_status_blocked
        }
        UploadWorkKind.POLL -> when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> R.string.queue_status_poll_waiting
            WorkInfo.State.SUCCEEDED -> if (deleted == false) {
                R.string.queue_status_poll_manual_delete
            } else {
                R.string.queue_status_poll_succeeded
            }
            WorkInfo.State.FAILED -> R.string.queue_status_poll_failed
            WorkInfo.State.CANCELLED -> R.string.queue_status_cancelled
            WorkInfo.State.BLOCKED -> R.string.queue_status_blocked
        }
    }
}

private const val DEFAULT_TITLE = "Загрузка"
private const val DEFAULT_PROGRESS_VALUE = -1
