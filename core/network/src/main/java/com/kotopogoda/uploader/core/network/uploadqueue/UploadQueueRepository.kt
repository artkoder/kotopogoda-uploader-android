package com.kotopogoda.uploader.core.network.uploadqueue

import android.net.Uri
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadQueueSnapshot
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
            .map { snapshots -> snapshots.map { it.toQueueItem() } }
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

    private fun UploadQueueSnapshot.toQueueItem(): UploadQueueItem {
        val metadata = UploadWorkMetadata(
            uniqueName = uniqueName,
            uri = runCatching { Uri.parse(uri) }.getOrNull(),
            displayName = displayName,
            idempotencyKey = idempotencyKey,
            kind = UploadWorkKind.UPLOAD,
        )
        return UploadQueueItem(
            id = UUID.nameUUIDFromBytes(uniqueName.toByteArray()),
            metadata = metadata,
            kind = metadata.kind,
            state = UploadQueueItemState.fromItemState(state),
            progress = null,
            progressDisplayName = displayName,
            bytesSent = null,
            totalBytes = null,
            lastErrorKind = UploadWorkErrorKind.fromRawValue(lastErrorKind),
            lastErrorHttpCode = lastErrorHttpCode,
            deleted = null,
        )
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
        fun fromItemState(state: UploadItemState): UploadQueueItemState {
            return when (state) {
                UploadItemState.PENDING -> ENQUEUED
                UploadItemState.RUNNING -> RUNNING
                UploadItemState.SUCCEEDED -> SUCCEEDED
                UploadItemState.FAILED -> FAILED
                UploadItemState.CANCELLED -> CANCELLED
            }
        }
    }
}
