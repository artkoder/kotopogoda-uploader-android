package com.kotopogoda.uploader.core.logging.diagnostics


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
