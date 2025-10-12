package com.kotopogoda.uploader.feature.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val uploadEnqueuer: UploadEnqueuer
) : ViewModel() {

    val uiState: StateFlow<QueueUiState> = uploadEnqueuer.getAllUploadsFlow()
        .map { infos ->
            val items = infos.map { it.toUiModel() }
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
        when {
            uniqueName != null -> uploadEnqueuer.cancel(uniqueName)
            metadata.uri != null -> uploadEnqueuer.cancel(metadata.uri)
        }
    }

    fun onRetry(item: QueueItemUiModel) {
        if (item.canRetry) {
            uploadEnqueuer.retry(item.metadata)
        }
    }

    private fun WorkInfo.toUiModel(): QueueItemUiModel {
        val metadata = UploadTags.metadataFrom(this)
        val progressValue = progress.getInt(UploadEnqueuer.KEY_PROGRESS, DEFAULT_PROGRESS_VALUE)
        val normalizedProgress = if (progressValue >= 0) progressValue.coerceIn(0, 100) else DEFAULT_PROGRESS_VALUE
        val progressName = progress.getString(UploadEnqueuer.KEY_DISPLAY_NAME)
        val displayName = when {
            !progressName.isNullOrBlank() -> progressName
            !metadata.displayName.isNullOrBlank() -> metadata.displayName
            metadata.uri != null -> metadata.uri.lastPathSegment ?: DEFAULT_TITLE
            else -> DEFAULT_TITLE
        }
        val canCancel = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
        val canRetry = state == WorkInfo.State.FAILED &&
            metadata.uniqueName != null &&
            metadata.uri != null &&
            metadata.idempotencyKey != null

        return QueueItemUiModel(
            id = id,
            title = displayName,
            progress = normalizedProgress,
            state = state,
            metadata = metadata,
            canCancel = canCancel,
            canRetry = canRetry
        )
    }

    companion object {
        private const val DEFAULT_TITLE = "Загрузка"
        private const val DEFAULT_PROGRESS_VALUE = -1
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
    val progress: Int,
    val state: WorkInfo.State,
    val metadata: UploadWorkMetadata,
    val canCancel: Boolean,
    val canRetry: Boolean
) {
    val isIndeterminate: Boolean get() = progress < 0
}
