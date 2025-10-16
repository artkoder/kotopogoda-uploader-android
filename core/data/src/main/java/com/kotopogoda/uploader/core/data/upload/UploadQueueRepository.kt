package com.kotopogoda.uploader.core.data.upload

import android.net.Uri
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import com.kotopogoda.uploader.core.work.UploadErrorKind
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadQueueRepository @Inject constructor(
    private val uploadItemDao: UploadItemDao,
    private val photoDao: PhotoDao,
    private val clock: Clock,
) {

    fun observeQueue(): Flow<List<UploadQueueEntry>> {
        return uploadItemDao.observeAll()
            .map { entities ->
                entities.mapNotNull { entity ->
                    val state = UploadItemState.fromRawValue(entity.state) ?: return@mapNotNull null
                    UploadQueueEntry(
                        entity = entity,
                        uri = entity.uri.toUriOrNull(),
                        state = state,
                        lastErrorKind = UploadErrorKind.fromRawValue(entity.lastErrorKind),
                        lastErrorHttpCode = entity.httpCode,
                    )
                }
            }
    }

    suspend fun enqueue(uri: Uri) = withContext(Dispatchers.IO) {
        val photo = photoDao.getByUri(uri.toString())
            ?: throw IllegalStateException("Photo not found for uri=$uri")
        val now = currentTimeMillis()
        val existing = uploadItemDao.getByPhotoId(photo.id)
        val displayName = buildDisplayName(photo.relPath, uri)
        if (existing == null) {
            uploadItemDao.insert(
                UploadItemEntity(
                    photoId = photo.id,
                    uri = photo.uri,
                    displayName = displayName,
                    size = photo.size,
                    state = UploadItemState.QUEUED.rawValue,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else {
            uploadItemDao.updateStateWithMetadata(
                id = existing.id,
                state = UploadItemState.QUEUED.rawValue,
                uri = photo.uri,
                displayName = displayName,
                size = photo.size,
                updatedAt = now,
            )
        }
    }

    suspend fun markQueued(uri: Uri) = withContext(Dispatchers.IO) {
        val photo = photoDao.getByUri(uri.toString()) ?: return@withContext
        val existing = uploadItemDao.getByPhotoId(photo.id) ?: return@withContext
        uploadItemDao.updateState(
            id = existing.id,
            state = UploadItemState.QUEUED.rawValue,
            updatedAt = currentTimeMillis(),
        )
    }

    suspend fun markCancelled(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val photo = photoDao.getByUri(uri.toString()) ?: return@withContext false
        val existing = uploadItemDao.getByPhotoId(photo.id) ?: return@withContext false
        val wasProcessing = existing.state == UploadItemState.PROCESSING.rawValue
        uploadItemDao.updateState(
            id = existing.id,
            state = UploadItemState.FAILED.rawValue,
            updatedAt = currentTimeMillis(),
        )
        wasProcessing
    }

    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        uploadItemDao.updateStatesClearingError(
            states = listOf(
                UploadItemState.QUEUED.rawValue,
                UploadItemState.PROCESSING.rawValue,
            ),
            state = UploadItemState.FAILED.rawValue,
            updatedAt = currentTimeMillis(),
        )
    }

    suspend fun recoverStuckProcessing(): Int = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        recoverStuckProcessingInternal(now)
    }

    suspend fun fetchQueued(limit: Int, recoverStuck: Boolean = true): List<UploadQueueItem> = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        if (recoverStuck) {
            recoverStuckProcessingInternal(now)
        }
        val queued = uploadItemDao.getByState(UploadItemState.QUEUED.rawValue, limit)
        val updateTimestamp = currentTimeMillis()
        buildList {
            for (entity in queued) {
                if (entity.uri.isBlank()) {
                    uploadItemDao.updateStateWithError(
                        id = entity.id,
                        state = UploadItemState.FAILED.rawValue,
                        lastErrorKind = UploadErrorKind.UNEXPECTED.rawValue,
                        httpCode = null,
                        updatedAt = updateTimestamp,
                    )
                    continue
                }
                val uri = runCatching { Uri.parse(entity.uri) }.getOrNull()
                if (uri == null) {
                    uploadItemDao.updateStateWithError(
                        id = entity.id,
                        state = UploadItemState.FAILED.rawValue,
                        lastErrorKind = UploadErrorKind.UNEXPECTED.rawValue,
                        httpCode = null,
                        updatedAt = updateTimestamp,
                    )
                    continue
                }
                val displayName = resolveDisplayName(entity, uri)
                val idempotencyKey = buildIdempotencyKey(entity.photoId)
                add(
                    UploadQueueItem(
                        id = entity.id,
                        uri = uri,
                        idempotencyKey = idempotencyKey,
                        displayName = displayName,
                        size = entity.size,
                    )
                )
            }
        }
    }

    suspend fun markProcessing(id: Long): Boolean = withContext(Dispatchers.IO) {
        val updatedRows = uploadItemDao.updateStateIfCurrent(
            id = id,
            expectedState = UploadItemState.QUEUED.rawValue,
            newState = UploadItemState.PROCESSING.rawValue,
            updatedAt = currentTimeMillis(),
        )
        updatedRows > 0
    }

    suspend fun markSucceeded(id: Long) = withContext(Dispatchers.IO) {
        uploadItemDao.updateState(
            id = id,
            state = UploadItemState.SUCCEEDED.rawValue,
            updatedAt = currentTimeMillis(),
        )
    }

    suspend fun markFailed(
        id: Long,
        errorKind: UploadErrorKind,
        httpCode: Int? = null,
        requeue: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val state = if (requeue) UploadItemState.QUEUED else UploadItemState.FAILED
        uploadItemDao.updateStateWithError(
            id = id,
            state = state.rawValue,
            lastErrorKind = errorKind.rawValue,
            httpCode = httpCode,
            updatedAt = currentTimeMillis(),
        )
    }

    suspend fun hasQueued(): Boolean = withContext(Dispatchers.IO) {
        uploadItemDao.countByState(UploadItemState.QUEUED.rawValue) > 0
    }

    suspend fun getState(id: Long): UploadItemState? = withContext(Dispatchers.IO) {
        val entity = uploadItemDao.getById(id) ?: return@withContext null
        UploadItemState.fromRawValue(entity.state)
    }

    private fun buildDisplayName(relPath: String?, uri: Uri): String {
        val fromRelPath = relPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val fromUri = uri.lastPathSegment?.takeIf { it.isNotBlank() }
        return fromRelPath ?: fromUri ?: DEFAULT_DISPLAY_NAME
    }

    private fun resolveDisplayName(entity: UploadItemEntity, uri: Uri): String {
        val stored = entity.displayName.takeIf { it.isNotBlank() && it != DEFAULT_DISPLAY_NAME }
        val normalizedStored = stored?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val fromUri = uri.lastPathSegment?.takeIf { it.isNotBlank() }
        return normalizedStored ?: fromUri ?: DEFAULT_DISPLAY_NAME
    }

    private fun buildIdempotencyKey(photoId: String): String {
        return photoId
    }

    private fun currentTimeMillis(): Long = clock.instant().toEpochMilli()

    private suspend fun recoverStuckProcessingInternal(now: Long): Int {
        return uploadItemDao.requeueProcessingToQueued(
            processingState = UploadItemState.PROCESSING.rawValue,
            queuedState = UploadItemState.QUEUED.rawValue,
            stuckBefore = now - PROCESSING_RECOVERY_TIMEOUT_MS,
            updatedAt = now,
        )
    }

    private fun String?.toUriOrNull(): Uri? {
        if (isNullOrBlank()) return null
        return runCatching { Uri.parse(this) }.getOrNull()
    }

    companion object {
        private const val DEFAULT_DISPLAY_NAME = "photo.jpg"
        internal const val PROCESSING_RECOVERY_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

data class UploadQueueEntry(
    val entity: UploadItemEntity,
    val uri: Uri?,
    val state: UploadItemState,
    val lastErrorKind: UploadErrorKind?,
    val lastErrorHttpCode: Int?,
)

data class UploadQueueItem(
    val id: Long,
    val uri: Uri,
    val idempotencyKey: String,
    val displayName: String,
    val size: Long,
)
