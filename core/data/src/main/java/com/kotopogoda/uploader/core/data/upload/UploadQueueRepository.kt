package com.kotopogoda.uploader.core.data.upload

import android.content.ContentResolver
import android.net.Uri
import com.kotopogoda.uploader.core.data.photo.MediaStorePhotoMetadata
import com.kotopogoda.uploader.core.data.photo.MediaStorePhotoMetadataReader
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import com.kotopogoda.uploader.core.data.photo.PhotoEntity
import com.kotopogoda.uploader.core.work.UploadErrorKind
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadQueueRepository @Inject constructor(
    private val uploadItemDao: UploadItemDao,
    private val photoDao: PhotoDao,
    private val metadataReader: MediaStorePhotoMetadataReader,
    private val contentResolver: ContentResolver,
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

    fun observeQueuedOrProcessing(photoId: String): Flow<Boolean> {
        return uploadItemDao.observeQueuedOrProcessingByPhotoId(
            photoId = photoId,
            queuedState = UploadItemState.QUEUED.rawValue,
            processingState = UploadItemState.PROCESSING.rawValue,
        ).distinctUntilChanged()
    }

    fun observeQueuedOrProcessing(uri: Uri): Flow<Boolean> {
        return uploadItemDao.observeQueuedOrProcessingByUri(
            uri = uri.toString(),
            queuedState = UploadItemState.QUEUED.rawValue,
            processingState = UploadItemState.PROCESSING.rawValue,
        ).distinctUntilChanged()
    }

    suspend fun enqueue(uri: Uri, idempotencyKey: String) = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        val photo = photoDao.getByUri(uriString)
            ?: createFallbackPhoto(uri, uriString)
        val now = currentTimeMillis()
        val existing = uploadItemDao.getByPhotoId(photo.id)
        val displayName = buildDisplayName(photo.relPath, uri)
        val effectiveIdempotencyKey = idempotencyKey.takeIf { it.isNotBlank() }
            ?: existing?.entityIdempotencyKey()
            ?: buildIdempotencyKey(photo.id)
        if (existing == null) {
            val id = uploadItemDao.insert(
                UploadItemEntity(
                    photoId = photo.id,
                    idempotencyKey = effectiveIdempotencyKey,
                    uri = photo.uri,
                    displayName = displayName,
                    size = photo.size,
                    state = UploadItemState.QUEUED.rawValue,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            Timber.tag("Queue").i(
                UploadLog.message(
                    action = "enqueue",
                    itemId = id,
                    photoId = photo.id,
                    uri = uri,
                    state = UploadItemState.QUEUED,
                    details = arrayOf(
                        "size" to photo.size,
                        "displayName" to displayName,
                        "existing" to false,
                    ),
                )
            )
        } else {
            uploadItemDao.updateStateWithMetadata(
                id = existing.id,
                state = UploadItemState.QUEUED.rawValue,
                uri = photo.uri,
                displayName = displayName,
                size = photo.size,
                idempotencyKey = effectiveIdempotencyKey,
                updatedAt = now,
            )
            Timber.tag("Queue").i(
                UploadLog.message(
                    action = "enqueue",
                    itemId = existing.id,
                    photoId = photo.id,
                    uri = uri,
                    state = UploadItemState.QUEUED,
                    details = arrayOf(
                        "size" to photo.size,
                        "displayName" to displayName,
                        "existing" to true,
                    ),
                )
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
        Timber.tag("Queue").i(
            UploadLog.message(
                action = "mark_queued",
                itemId = existing.id,
                photoId = photo.id,
                uri = uri,
                state = UploadItemState.QUEUED,
            )
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
        Timber.tag("Queue").i(
            UploadLog.message(
                action = "mark_cancelled",
                itemId = existing.id,
                photoId = photo.id,
                uri = uri,
                state = UploadItemState.FAILED,
                details = arrayOf(
                    "wasProcessing" to wasProcessing,
                ),
            )
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
        Timber.tag("Queue").w(
            UploadLog.message(
                action = "cancel_all",
                state = UploadItemState.FAILED,
            )
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
        val items = buildList {
            for (entity in queued) {
                if (entity.uri.isBlank()) {
                    uploadItemDao.updateStateWithError(
                        id = entity.id,
                        state = UploadItemState.FAILED.rawValue,
                        lastErrorKind = UploadErrorKind.UNEXPECTED.rawValue,
                        httpCode = null,
                        updatedAt = updateTimestamp,
                    )
                    Timber.tag("Queue").w(
                        UploadLog.message(
                            action = "fetch_queued_skip",
                            itemId = entity.id,
                            state = UploadItemState.FAILED,
                            details = arrayOf(
                                "reason" to "missing_uri",
                            ),
                        )
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
                    Timber.tag("Queue").w(
                        UploadLog.message(
                            action = "fetch_queued_skip",
                            itemId = entity.id,
                            state = UploadItemState.FAILED,
                            details = arrayOf(
                                "reason" to "invalid_uri",
                            ),
                        )
                    )
                    continue
                }
                val displayName = resolveDisplayName(entity, uri)
                val idempotencyKey = entity.idempotencyKey.takeIf { it.isNotBlank() }
                    ?: buildIdempotencyKey(entity.photoId)
                add(
                    UploadQueueItem(
                        id = entity.id,
                        uri = uri,
                        idempotencyKey = idempotencyKey,
                        displayName = displayName,
                        size = entity.size,
                    )
                )
                Timber.tag("Queue").i(
                    UploadLog.message(
                        action = "fetch_queued_item",
                        itemId = entity.id,
                        photoId = entity.photoId,
                        uri = uri,
                        state = UploadItemState.QUEUED,
                        details = arrayOf(
                            "displayName" to displayName,
                            "size" to entity.size,
                        ),
                    )
                )
            }
        }
        Timber.tag("Queue").i(
            UploadLog.message(
                action = "fetch_queued_summary",
                state = UploadItemState.QUEUED,
                details = arrayOf(
                    "requested" to limit,
                    "returned" to items.size,
                ),
            )
        )
        items
    }

    suspend fun markProcessing(id: Long): Boolean = withContext(Dispatchers.IO) {
        val updatedRows = uploadItemDao.updateStateIfCurrent(
            id = id,
            expectedState = UploadItemState.QUEUED.rawValue,
            newState = UploadItemState.PROCESSING.rawValue,
            updatedAt = currentTimeMillis(),
        )
        val success = updatedRows > 0
        val action = if (success) "mark_processing" else "mark_processing_skipped"
        Timber.tag("Queue").i(
            UploadLog.message(
                action = action,
                itemId = id,
                state = if (success) UploadItemState.PROCESSING else null,
            )
        )
        success
    }

    suspend fun markSucceeded(id: Long) = withContext(Dispatchers.IO) {
        uploadItemDao.updateState(
            id = id,
            state = UploadItemState.SUCCEEDED.rawValue,
            updatedAt = currentTimeMillis(),
        )
        Timber.tag("Queue").i(
            UploadLog.message(
                action = "mark_succeeded",
                itemId = id,
                state = UploadItemState.SUCCEEDED,
            )
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
        Timber.tag("Queue").w(
            UploadLog.message(
                action = "mark_failed",
                itemId = id,
                state = state,
                details = arrayOf(
                    "errorKind" to errorKind,
                    "httpCode" to httpCode,
                    "requeue" to requeue,
                ),
            )
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

    private suspend fun createFallbackPhoto(uri: Uri, uriString: String): PhotoEntity {
        val metadata = metadataReader.read(uri)
        val entity = PhotoEntity(
            id = uriString,
            uri = uriString,
            relPath = buildRelPath(metadata, uri),
            sha256 = uriString,
            takenAt = resolveTakenAt(metadata),
            size = metadata?.size ?: 0L,
            mime = metadata?.mimeType ?: DEFAULT_MIME,
        )
        val inputStream = try {
            contentResolver.openInputStream(uri)
        } catch (error: Exception) {
            throw IllegalStateException("Unable to open input stream for uri: $uri", error)
        }
        inputStream?.use { }
            ?: throw IllegalStateException("Unable to open input stream for uri: $uri")
        try {
            photoDao.upsert(entity)
        } catch (error: Throwable) {
            Timber.tag("Queue").w(
                error,
                "Failed to upsert fallback photo metadata for %s",
                uri,
            )
        }
        return entity
    }

    private fun buildRelPath(metadata: MediaStorePhotoMetadata?, uri: Uri): String? {
        val displayName = metadata?.displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        val relative = metadata?.relativePath?.trimEnd('/')?.takeIf { it.isNotBlank() }
        return when {
            relative != null && displayName != null -> "$relative/$displayName"
            displayName != null -> displayName
            else -> relative
        }
    }

    private fun resolveTakenAt(metadata: MediaStorePhotoMetadata?): Long? {
        return metadata?.dateTakenMillis
            ?: metadata?.dateAddedMillis
            ?: metadata?.dateModifiedMillis
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

    private fun UploadItemEntity.entityIdempotencyKey(): String? {
        return idempotencyKey.takeIf { it.isNotBlank() }
    }

    private suspend fun recoverStuckProcessingInternal(now: Long): Int {
        val requeued = uploadItemDao.requeueProcessingToQueued(
            processingState = UploadItemState.PROCESSING.rawValue,
            queuedState = UploadItemState.QUEUED.rawValue,
            updatedAt = now,
        )
        if (requeued > 0) {
            Timber.tag("Queue").w(
                UploadLog.message(
                    action = "recover_processing",
                    state = UploadItemState.QUEUED,
                    details = arrayOf(
                        "requeued" to requeued,
                    ),
                )
            )
        }
        return requeued
    }

    private fun String?.toUriOrNull(): Uri? {
        if (isNullOrBlank()) return null
        return runCatching { Uri.parse(this) }.getOrNull()
    }

    companion object {
        private const val DEFAULT_DISPLAY_NAME = "photo.jpg"
        private const val DEFAULT_MIME = "image/jpeg"
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
