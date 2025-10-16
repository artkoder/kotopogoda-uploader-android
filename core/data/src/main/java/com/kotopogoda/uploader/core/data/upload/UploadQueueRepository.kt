package com.kotopogoda.uploader.core.data.upload

import android.net.Uri
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import com.kotopogoda.uploader.core.network.upload.UploadWorkErrorKind
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class UploadQueueRepository @Inject constructor(
    private val uploadItemDao: UploadItemDao,
    private val photoDao: PhotoDao,
    private val clock: Clock,
) {

    suspend fun fetchQueued(limit: Int): List<UploadQueueItem> = withContext(Dispatchers.IO) {
        val queued = uploadItemDao.getByState(UploadItemState.QUEUED.rawValue, limit)
        val now = currentTimeMillis()
        buildList {
            for (entity in queued) {
                val photo = photoDao.getById(entity.photoId)
                if (photo == null) {
                    uploadItemDao.updateStateWithError(
                        id = entity.id,
                        state = UploadItemState.FAILED.rawValue,
                        lastErrorKind = UploadWorkErrorKind.UNEXPECTED.rawValue,
                        httpCode = null,
                        updatedAt = now,
                    )
                    continue
                }
                val uri = runCatching { Uri.parse(photo.uri) }.getOrNull()
                if (uri == null) {
                    uploadItemDao.updateStateWithError(
                        id = entity.id,
                        state = UploadItemState.FAILED.rawValue,
                        lastErrorKind = UploadWorkErrorKind.UNEXPECTED.rawValue,
                        httpCode = null,
                        updatedAt = now,
                    )
                    continue
                }
                val displayName = buildDisplayName(photo.relPath, uri)
                val idempotencyKey = buildIdempotencyKey(photo.sha256, photo.id)
                add(
                    UploadQueueItem(
                        id = entity.id,
                        uri = uri,
                        idempotencyKey = idempotencyKey,
                        displayName = displayName,
                    )
                )
            }
        }
    }

    suspend fun markProcessing(id: Long) = withContext(Dispatchers.IO) {
        uploadItemDao.updateState(
            id = id,
            state = UploadItemState.PROCESSING.rawValue,
            updatedAt = currentTimeMillis(),
        )
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
        errorKind: UploadWorkErrorKind,
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

    private fun buildDisplayName(relPath: String?, uri: Uri): String {
        val fromRelPath = relPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val fromUri = uri.lastPathSegment?.takeIf { it.isNotBlank() }
        return fromRelPath ?: fromUri ?: DEFAULT_DISPLAY_NAME
    }

    private fun buildIdempotencyKey(sha256: String, fallback: String): String {
        return if (sha256.isNotBlank()) sha256 else fallback
    }

    private fun currentTimeMillis(): Long = clock.instant().toEpochMilli()

    companion object {
        private const val DEFAULT_DISPLAY_NAME = "photo.jpg"
    }
}

data class UploadQueueItem(
    val id: Long,
    val uri: Uri,
    val idempotencyKey: String,
    val displayName: String,
)
