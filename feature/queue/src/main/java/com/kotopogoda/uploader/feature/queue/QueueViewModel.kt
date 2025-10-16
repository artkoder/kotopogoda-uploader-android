package com.kotopogoda.uploader.feature.queue

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueEntry
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.kotopogoda.uploader.feature.queue.R

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadEnqueuer: UploadEnqueuer,
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
        val uri = item.uri ?: return
        viewModelScope.launch {
            uploadEnqueuer.cancel(uri)
        }
    }

    fun onRetry(item: QueueItemUiModel) {
        if (!item.canRetry) return
        viewModelScope.launch {
            val metadata = buildMetadata(item) ?: return@launch
            uploadEnqueuer.retry(metadata)
        }
    }

    fun ensureSummaryRunning() {
        summaryStarter.ensureRunning()
    }

    fun startUploadProcessing() {
        uploadEnqueuer.ensureUploadRunning()
    }

}

data class QueueUiState(
    val items: List<QueueItemUiModel> = emptyList()
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

data class QueueItemUiModel(
    val id: Long,
    val title: String,
    val progressPercent: Int?,
    val uri: Uri?,
    val source: UploadQueueEntry,
    val canCancel: Boolean,
    val canRetry: Boolean,
    @StringRes val statusResId: Int,
    val highlightWarning: Boolean,
    val bytesSent: Long?,
    val totalBytes: Long?,
    val lastErrorKind: UploadErrorKind?,
    val lastErrorHttpCode: Int?,
) {
    val isIndeterminate: Boolean get() = progressPercent == null
}

internal fun UploadQueueEntry.toQueueItemUiModel(): QueueItemUiModel {
    val entity = this.entity
    val normalizedTitle = entity.displayName.takeIf { it.isNotBlank() }
        ?: uri?.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: DEFAULT_TITLE
    val progressPercent = when (state) {
        UploadItemState.QUEUED -> 0
        UploadItemState.PROCESSING -> null
        UploadItemState.SUCCEEDED -> 100
        UploadItemState.FAILED -> 0
    }
    val statusResId = when (state) {
        UploadItemState.QUEUED -> R.string.queue_status_enqueued
        UploadItemState.PROCESSING -> R.string.queue_status_running
        UploadItemState.SUCCEEDED -> R.string.queue_status_succeeded
        UploadItemState.FAILED -> R.string.queue_status_failed
    }
    val canCancel = state == UploadItemState.QUEUED || state == UploadItemState.PROCESSING
    val canRetry = state == UploadItemState.FAILED
    val highlightWarning = state == UploadItemState.FAILED && lastErrorKind == UploadErrorKind.REMOTE_FAILURE

    return QueueItemUiModel(
        id = entity.id,
        title = normalizedTitle,
        progressPercent = progressPercent,
        uri = uri,
        source = this,
        canCancel = canCancel,
        canRetry = canRetry,
        statusResId = statusResId,
        highlightWarning = highlightWarning,
        bytesSent = null,
        totalBytes = entity.size.takeIf { it > 0 },
        lastErrorKind = lastErrorKind,
        lastErrorHttpCode = lastErrorHttpCode,
    )
}

private fun QueueViewModel.buildMetadata(item: QueueItemUiModel): UploadWorkMetadata? {
    val uri = item.uri ?: return null
    val entity = item.source.entity
    val displayName = entity.displayName.takeIf { it.isNotBlank() } ?: item.title
    val idempotencyKey = entity.photoId
    if (idempotencyKey.isBlank()) {
        return null
    }
    return UploadWorkMetadata(
        uniqueName = uploadEnqueuer.uniqueName(uri),
        uri = uri,
        displayName = displayName,
        idempotencyKey = idempotencyKey,
        kind = UploadWorkKind.UPLOAD,
    )
}

private const val DEFAULT_TITLE = "Загрузка"
