package com.kotopogoda.uploader.core.network.uploadqueue

import androidx.work.WorkInfo
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkErrorKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkMetadata
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class UploadQueueRepository @Inject constructor(
    private val uploadEnqueuer: UploadEnqueuer,
) {

    fun observeQueue(): Flow<List<UploadQueueItem>> {
        return uploadEnqueuer.getAllUploadsFlow()
            .map { infos -> infos.map { it.toQueueItem() } }
    }

    suspend fun cancel(item: UploadQueueItem) {
        val metadata = item.metadata
        val uniqueName = metadata.uniqueName
        if (uniqueName != null) {
            uploadEnqueuer.cancel(uniqueName)
        } else {
            metadata.uri?.let(uploadEnqueuer::cancel)
        }
    }

    suspend fun retry(item: UploadQueueItem) {
        if (!item.isRetryable) return
        uploadEnqueuer.retry(item.metadata)
    }

    private val UploadQueueItem.isRetryable: Boolean
        get() {
            val metadata = this.metadata
            return state == UploadQueueItemState.FAILED &&
                metadata.uniqueName != null &&
                metadata.uri != null &&
                metadata.idempotencyKey != null &&
                metadata.kind == UploadWorkKind.UPLOAD
        }

    private fun WorkInfo.toQueueItem(): UploadQueueItem {
        val metadata = UploadTags.metadataFrom(this)
        val progressValue = progress.getInt(UploadEnqueuer.KEY_PROGRESS, DEFAULT_PROGRESS_VALUE)
        val normalizedProgress = progressValue.takeIf { it >= 0 }?.coerceIn(0, 100)
        val progressDisplayName = progress.getString(UploadEnqueuer.KEY_DISPLAY_NAME)

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

        val deleted = outputMap[UploadEnqueuer.KEY_DELETED] as? Boolean

        return UploadQueueItem(
            id = id,
            metadata = metadata,
            kind = metadata.kind,
            state = UploadQueueItemState.fromWorkState(state),
            progress = normalizedProgress,
            progressDisplayName = progressDisplayName,
            bytesSent = bytesSent,
            totalBytes = totalBytes,
            lastErrorKind = errorKind,
            lastErrorHttpCode = errorHttpCode,
            deleted = deleted,
        )
    }

    companion object {
        private const val DEFAULT_PROGRESS_VALUE = -1
    }
}

data class UploadQueueItem(
    val id: UUID,
    val metadata: UploadWorkMetadata,
    val kind: UploadWorkKind,
    val state: UploadQueueItemState,
    val progress: Int?,
    val progressDisplayName: String?,
    val bytesSent: Long?,
    val totalBytes: Long?,
    val lastErrorKind: UploadWorkErrorKind?,
    val lastErrorHttpCode: Int?,
    val deleted: Boolean?,
)

enum class UploadQueueItemState {
    ENQUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    BLOCKED;

    companion object {
        fun fromWorkState(state: WorkInfo.State): UploadQueueItemState {
            return when (state) {
                WorkInfo.State.ENQUEUED -> ENQUEUED
                WorkInfo.State.RUNNING -> RUNNING
                WorkInfo.State.SUCCEEDED -> SUCCEEDED
                WorkInfo.State.FAILED -> FAILED
                WorkInfo.State.CANCELLED -> CANCELLED
                WorkInfo.State.BLOCKED -> BLOCKED
            }
        }
    }
}
