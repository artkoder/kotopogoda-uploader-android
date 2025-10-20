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
    val state: String,
    val createdAt: Long,
    val updatedAt: Long?,
    val ageMillis: Long,
    val timeSinceUpdateMillis: Long,
    val lastErrorKind: String?,
    val lastErrorHttpCode: Int?,
)

data class QueueStatsSnapshot(
    val waiting: Int,
    val running: Int,
    val succeeded: Int,
    val failed: Int,
)

interface UploadQueueSnapshotProvider {
    suspend fun getQueued(limit: Int): List<QueueItemSnapshot>
    suspend fun getStats(): QueueStatsSnapshot
}

@Singleton
class RepositoryUploadQueueSnapshotProvider @Inject constructor(
    private val repository: UploadQueueRepository,
) : UploadQueueSnapshotProvider {
    override suspend fun getQueued(limit: Int): List<QueueItemSnapshot> {
        val now = System.currentTimeMillis()
        return repository.fetchQueued(limit, recoverStuck = false).map { item ->
            val lastUpdatedAt = item.updatedAt ?: item.createdAt
            QueueItemSnapshot(
                id = item.id,
                uri = item.uri.toString(),
                displayName = item.displayName,
                idempotencyKey = item.idempotencyKey,
                size = item.size,
                state = item.state.rawValue,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt,
                ageMillis = (now - item.createdAt).coerceAtLeast(0),
                timeSinceUpdateMillis = (now - lastUpdatedAt).coerceAtLeast(0),
                lastErrorKind = item.lastErrorKind?.rawValue,
                lastErrorHttpCode = item.lastErrorHttpCode,
            )
        }
    }

    override suspend fun getStats(): QueueStatsSnapshot {
        val stats = repository.getQueueStats()
        return QueueStatsSnapshot(
            waiting = stats.queued,
            running = stats.processing,
            succeeded = stats.succeeded,
            failed = stats.failed,
        )
    }
}
