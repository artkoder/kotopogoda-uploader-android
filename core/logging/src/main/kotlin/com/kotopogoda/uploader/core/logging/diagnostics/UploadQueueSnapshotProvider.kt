package com.kotopogoda.uploader.core.logging.diagnostics

import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import javax.inject.Inject
import javax.inject.Singleton

data class QueueItemSnapshot(
    val id: Long,
    val uri: String?,
    val displayName: String?,
    val idempotencyKey: String?,
    val size: Long?,
)

interface UploadQueueSnapshotProvider {
    suspend fun getQueued(limit: Int): List<QueueItemSnapshot>
}

@Singleton
class RepositoryUploadQueueSnapshotProvider @Inject constructor(
    private val repository: UploadQueueRepository,
) : UploadQueueSnapshotProvider {
    override suspend fun getQueued(limit: Int): List<QueueItemSnapshot> {
        return repository.fetchQueued(limit, recoverStuck = false).map { item ->
            QueueItemSnapshot(
                id = item.id,
                uri = item.uri.toString(),
                displayName = item.displayName,
                idempotencyKey = item.idempotencyKey,
                size = item.size,
            )
        }
    }
}
