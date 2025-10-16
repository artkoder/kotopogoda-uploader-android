package com.kotopogoda.uploader.feature.queue

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkErrorKind
import com.kotopogoda.uploader.core.network.uploadqueue.UploadQueueItem
import com.kotopogoda.uploader.core.network.uploadqueue.UploadQueueItemState
import com.kotopogoda.uploader.core.network.uploadqueue.UploadQueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import com.kotopogoda.uploader.feature.queue.R

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val uploadQueueRepository: UploadQueueRepository,
    private val summaryStarter: UploadSummaryStarter,
) : ViewModel() {

    init {
        summaryStarter.ensureRunning()
    }

    val uiState: StateFlow<QueueUiState> = uploadQueueRepository.observeQueue()
        .map { items ->
            val uiItems = items.map { it.toQueueItemUiModel() }
            QueueUiState(items = uiItems)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = QueueUiState()
        )

    fun onCancel(item: QueueItemUiModel) {
        viewModelScope.launch {
            uploadQueueRepository.cancel(item.source)
        }
    }

    fun onRetry(item: QueueItemUiModel) {
        if (!item.canRetry) return
        viewModelScope.launch {
            uploadQueueRepository.retry(item.source)
        }
    }

    fun ensureSummaryRunning() {
        summaryStarter.ensureRunning()
    }

}

data class QueueUiState(
    val items: List<QueueItemUiModel> = emptyList()
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

data class QueueItemUiModel(
    val id: UUID,
    val title: String,
    val progressPercent: Int?,
    val source: UploadQueueItem,
    val canCancel: Boolean,
    val canRetry: Boolean,
    @StringRes val statusResId: Int,
    val highlightWarning: Boolean,
    val bytesSent: Long?,
    val totalBytes: Long?,
    val lastErrorKind: UploadWorkErrorKind?,
    val lastErrorHttpCode: Int?,
) {
    val isIndeterminate: Boolean get() = progressPercent == null
}

internal fun UploadQueueItem.toQueueItemUiModel(): QueueItemUiModel {
    val metadata = this.metadata
    val metadataUri = metadata.uri
    val metadataDisplayName = metadata.displayName
    val metadataUniqueName = metadata.uniqueName
    val metadataIdempotencyKey = metadata.idempotencyKey

    val progressValue = this.progress
    val normalizedProgress = progressValue?.coerceIn(0, 100)
    val progressName = this.progressDisplayName
    val displayName = progressName?.takeIf { it.isNotBlank() }
        ?: metadataDisplayName?.takeIf { it.isNotBlank() }
        ?: metadataUri?.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: DEFAULT_TITLE

    val canCancel = state == UploadQueueItemState.ENQUEUED || state == UploadQueueItemState.RUNNING
    val canRetry = state == UploadQueueItemState.FAILED &&
        metadataUniqueName != null &&
        metadataUri != null &&
        metadataIdempotencyKey != null &&
        metadata.kind == UploadWorkKind.UPLOAD
    val deleted = this.deleted
    val statusResId = statusResId(state, metadata.kind, deleted)
    val highlightWarning = metadata.kind == UploadWorkKind.POLL &&
        state == UploadQueueItemState.SUCCEEDED && deleted == false

    return QueueItemUiModel(
        id = id,
        title = displayName,
        progressPercent = normalizedProgress,
        source = this,
        canCancel = canCancel,
        canRetry = canRetry,
        statusResId = statusResId,
        highlightWarning = highlightWarning,
        bytesSent = this.bytesSent,
        totalBytes = this.totalBytes,
        lastErrorKind = this.lastErrorKind,
        lastErrorHttpCode = this.lastErrorHttpCode,
    )
}

@StringRes
private fun statusResId(
    state: UploadQueueItemState,
    kind: UploadWorkKind,
    deleted: Boolean?,
): Int {
    return when (kind) {
        UploadWorkKind.UPLOAD -> when (state) {
            UploadQueueItemState.ENQUEUED -> R.string.queue_status_enqueued
            UploadQueueItemState.RUNNING -> R.string.queue_status_running
            UploadQueueItemState.SUCCEEDED -> R.string.queue_status_succeeded
            UploadQueueItemState.FAILED -> R.string.queue_status_failed
            UploadQueueItemState.CANCELLED -> R.string.queue_status_cancelled
            UploadQueueItemState.BLOCKED -> R.string.queue_status_blocked
        }
        UploadWorkKind.POLL -> when (state) {
            UploadQueueItemState.ENQUEUED, UploadQueueItemState.RUNNING -> R.string.queue_status_poll_waiting
            UploadQueueItemState.SUCCEEDED -> if (deleted == false) {
                R.string.queue_status_poll_manual_delete
            } else {
                R.string.queue_status_poll_succeeded
            }
            UploadQueueItemState.FAILED -> R.string.queue_status_poll_failed
            UploadQueueItemState.CANCELLED -> R.string.queue_status_cancelled
            UploadQueueItemState.BLOCKED -> R.string.queue_status_blocked
        }
    }
}

private const val DEFAULT_TITLE = "Загрузка"
