package com.kotopogoda.uploader.feature.queue

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueEntry
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkMetadata
import com.kotopogoda.uploader.core.network.upload.UploadTags
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.kotopogoda.uploader.feature.queue.R

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadEnqueuer: UploadEnqueuer,
    private val summaryStarter: UploadSummaryStarter,
    private val workManager: WorkManager,
    private val workInfoMapper: QueueWorkInfoMapper,
) : ViewModel() {

    init {
        summaryStarter.ensureRunning()
    }

    private val workInfoFlow = workManager.getWorkInfosByTagFlow(UploadTags.TAG_UPLOAD)
        .map { infos -> buildWorkLookup(infos) }

    val uiState: StateFlow<QueueUiState> = combine(
        uploadQueueRepository.observeQueue(),
        workInfoFlow,
    ) { items, lookup ->
        val uiItems = items.map { entry ->
            val workInfo = findWorkInfo(entry, lookup)
            entry.toQueueItemUiModel(workInfo)
        }
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

    private fun buildMetadata(item: QueueItemUiModel): UploadWorkMetadata? {
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

    private fun buildWorkLookup(infos: List<WorkInfo>): QueueWorkLookup {
        if (infos.isEmpty()) {
            return QueueWorkLookup.EMPTY
        }
        val mapped = infos.mapNotNull(workInfoMapper::map)
        if (mapped.isEmpty()) {
            return QueueWorkLookup.EMPTY
        }
        val byUniqueName = buildMap {
            for (info in mapped) {
                val key = info.uniqueName
                if (!key.isNullOrBlank()) {
                    put(key, info)
                }
            }
        }
        val byUri = buildMap {
            for (info in mapped) {
                val key = info.uri?.toString()
                if (!key.isNullOrBlank()) {
                    put(key, info)
                }
            }
        }
        return QueueWorkLookup(byUniqueName, byUri)
    }

    private fun findWorkInfo(
        entry: UploadQueueEntry,
        lookup: QueueWorkLookup,
    ): QueueItemWorkInfo? {
        val uri = entry.uri
        if (uri != null) {
            val uniqueName = uploadEnqueuer.uniqueName(uri)
            lookup.byUniqueName[uniqueName]?.let { return it }
            lookup.byUri[uri.toString()]?.let { return it }
        }
        val entityUri = entry.entity.uri
        if (entityUri.isNotBlank()) {
            lookup.byUri[entityUri]?.let { return it }
        }
        return null
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
    val waitingReasons: List<QueueItemWaitingReason>,
    val isActiveTransfer: Boolean,
) {
    val isIndeterminate: Boolean get() = progressPercent == null
}

internal fun UploadQueueEntry.toQueueItemUiModel(
    workInfo: QueueItemWorkInfo?,
): QueueItemUiModel {
    val entity = this.entity
    val normalizedTitle = entity.displayName.takeIf { it.isNotBlank() }
        ?: uri?.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: DEFAULT_TITLE
    val baseProgressPercent = when (state) {
        UploadItemState.QUEUED -> 0
        UploadItemState.PROCESSING -> null
        UploadItemState.SUCCEEDED -> 100
        UploadItemState.FAILED -> 0
    }
    val baseStatusResId = when (state) {
        UploadItemState.QUEUED -> R.string.queue_status_enqueued
        UploadItemState.PROCESSING -> R.string.queue_status_running
        UploadItemState.SUCCEEDED -> R.string.queue_status_succeeded
        UploadItemState.FAILED -> R.string.queue_status_failed
    }
    val canCancel = state == UploadItemState.QUEUED || state == UploadItemState.PROCESSING
    val canRetry = state == UploadItemState.FAILED
    val highlightWarning = state == UploadItemState.FAILED && lastErrorKind == UploadErrorKind.REMOTE_FAILURE

    val mergedProgressPercent = workInfo?.progressPercent ?: baseProgressPercent
    val mergedStatusResId = workInfo?.statusResId ?: baseStatusResId
    val mergedBytesSent = workInfo?.bytesSent
    val mergedTotalBytes = workInfo?.totalBytes ?: entity.size.takeIf { it > 0 }
    val mergedWaitingReasons = workInfo?.waitingReasons ?: emptyList()
    val mergedIsActive = workInfo?.isActiveTransfer ?: false
    val finalCanCancel = when (workInfo?.state) {
        WorkInfo.State.SUCCEEDED,
        WorkInfo.State.FAILED,
        WorkInfo.State.CANCELLED -> false
        else -> canCancel
    }

    return QueueItemUiModel(
        id = entity.id,
        title = normalizedTitle,
        progressPercent = mergedProgressPercent,
        uri = uri,
        source = this,
        canCancel = finalCanCancel,
        canRetry = canRetry,
        statusResId = mergedStatusResId,
        highlightWarning = highlightWarning,
        bytesSent = mergedBytesSent,
        totalBytes = mergedTotalBytes,
        lastErrorKind = lastErrorKind,
        lastErrorHttpCode = lastErrorHttpCode,
        waitingReasons = mergedWaitingReasons,
        isActiveTransfer = mergedIsActive,
    )
}

data class QueueWorkLookup(
    val byUniqueName: Map<String, QueueItemWorkInfo>,
    val byUri: Map<String, QueueItemWorkInfo>,
) {
    companion object {
        val EMPTY = QueueWorkLookup(emptyMap(), emptyMap())
    }
}

private const val DEFAULT_TITLE = "Загрузка"
