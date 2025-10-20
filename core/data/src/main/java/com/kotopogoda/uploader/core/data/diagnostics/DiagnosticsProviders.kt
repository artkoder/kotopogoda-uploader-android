package com.kotopogoda.uploader.core.data.diagnostics

import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.logging.diagnostics.FolderSelectionProvider
import com.kotopogoda.uploader.core.logging.diagnostics.FolderSnapshot
import com.kotopogoda.uploader.core.logging.diagnostics.QueueItemSnapshot
import com.kotopogoda.uploader.core.logging.diagnostics.QueueStatsSnapshot
import com.kotopogoda.uploader.core.logging.diagnostics.UploadQueueSnapshotProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepositoryFolderSelectionProvider @Inject constructor(
    private val repository: FolderRepository,
) : FolderSelectionProvider {
    override suspend fun currentSelection(): FolderSnapshot? {
        return repository.getFolder()?.let { folder ->
            FolderSnapshot(
                treeUri = folder.treeUri,
                flags = folder.flags,
                lastScanAt = folder.lastScanAt,
                lastViewedPhotoId = folder.lastViewedPhotoId,
                lastViewedAt = folder.lastViewedAt,
            )
        }
    }
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
