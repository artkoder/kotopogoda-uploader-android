package com.kotopogoda.uploader.core.data.upload

import android.content.ContentResolver
import android.net.Uri
import com.kotopogoda.uploader.core.data.photo.MediaStorePhotoMetadata
import com.kotopogoda.uploader.core.data.photo.MediaStorePhotoMetadataReader
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import com.kotopogoda.uploader.core.data.photo.PhotoEntity
import com.kotopogoda.uploader.core.data.upload.contentSha256FromIdempotencyKey
import com.kotopogoda.uploader.core.data.upload.idempotencyKeyFromContentSha256
import com.kotopogoda.uploader.core.data.util.Hashing
import com.kotopogoda.uploader.core.work.UploadErrorKind
import java.time.Clock
import kotlinx.coroutines.CancellationException
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
    private val successListeners: Set<UploadSuccessListener>,
) {

    fun observeQueue(): Flow<List<UploadQueueEntry>> {
        return uploadItemDao.observeAll()
            .map { entities ->
                val succeededCutoff = currentTimeMillis() - SUCCEEDED_RETENTION_MS
                entities.asSequence()
                    .filterNot { entity -> entity.isExpiredSucceeded(succeededCutoff) }
                    .mapNotNull { entity ->
                    val state = UploadItemState.fromRawValue(entity.state) ?: return@mapNotNull null
                        UploadQueueEntry(
                            entity = entity,
                            uri = entity.uri.toUriOrNull(),
                            state = state,
                            lastErrorKind = UploadErrorKind.fromRawValue(entity.lastErrorKind),
                            lastErrorHttpCode = entity.httpCode,
                            lastErrorMessage = entity.lastErrorMessage,
                            locationHiddenBySystem = entity.locationHiddenBySystem,
                        )
                    }
                    .toList()
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

    suspend fun enqueue(
        uri: Uri,
        idempotencyKey: String,
        contentSha256: String?,
        options: UploadEnqueueOptions = UploadEnqueueOptions(),
    ) = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        val initialPhoto = when (val photoId = options.photoId) {
            null -> photoDao.getByUri(uriString)
                ?: createFallbackPhoto(uri, uriString, contentSha256)
            else -> photoDao.getById(photoId)
                ?: photoDao.getByUri(uriString)
                ?: createFallbackPhoto(uri, uriString, contentSha256)
        }
        val shouldUpdateDigest = !contentSha256.isNullOrBlank() && options.enhancement == null
        val photo = if (shouldUpdateDigest && initialPhoto.sha256 != contentSha256) {
            val updated = initialPhoto.copy(sha256 = requireNotNull(contentSha256))
            runCatching { photoDao.upsert(updated) }.onFailure { error ->
                Timber.tag("Queue").w(error, "Failed to update photo hash for %s", uri)
            }
            updated
        } else {
            initialPhoto
        }
        val now = currentTimeMillis()
        val existing = uploadItemDao.getByPhotoId(photo.id)
        val displayName = options.overrideDisplayName
            ?: buildDisplayName(photo.relPath, uri)
        val resolvedSize = options.overrideSize ?: photo.size
        val enhancement = options.enhancement
        val effectiveIdempotencyKey = idempotencyKey.takeIf { it.isNotBlank() }
            ?: existing?.entityIdempotencyKey()
            ?: buildIdempotencyKey(photo)
        Timber.tag("Queue").i(
            UploadLog.message(
                category = CATEGORY_ENQUEUE_REQUEST,
                action = "enqueue",
                photoId = photo.id,
                uri = uri,
                details = arrayOf(
                    "existing" to (existing != null),
                    "idempotency_key" to effectiveIdempotencyKey,
                    "enhanced" to (enhancement != null),
                ),
            ),
        )
        if (existing == null) {
            val id = uploadItemDao.insert(
                UploadItemEntity(
                    photoId = photo.id,
                    idempotencyKey = effectiveIdempotencyKey,
                    uri = uriString,
                    displayName = displayName,
                    size = resolvedSize,
                    enhanced = enhancement != null,
                    enhanceStrength = enhancement?.strength,
                    enhanceDelegate = enhancement?.delegate,
                    enhanceMetricsLMean = enhancement?.metrics?.lMean,
                    enhanceMetricsPDark = enhancement?.metrics?.pDark,
                    enhanceMetricsBSharpness = enhancement?.metrics?.bSharpness,
                    enhanceMetricsNNoise = enhancement?.metrics?.nNoise,
                    state = UploadItemState.QUEUED.rawValue,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            Timber.tag("Queue").i(
                UploadLog.message(
                    category = CATEGORY_ENQUEUE_OK,
                    action = "enqueue_inserted",
                    photoId = photo.id,
                    uri = uri,
                    state = UploadItemState.QUEUED,
                    details = arrayOf(
                        "queue_item_id" to id,
                        "size" to resolvedSize,
                        "display_name" to displayName,
                        "existing" to false,
                    ),
                ),
            )
        } else {
            uploadItemDao.updateStateWithMetadata(
                id = existing.id,
                state = UploadItemState.QUEUED.rawValue,
                uri = uriString,
                displayName = displayName,
                size = resolvedSize,
                idempotencyKey = effectiveIdempotencyKey,
                enhanced = enhancement != null,
                enhanceStrength = enhancement?.strength,
                enhanceDelegate = enhancement?.delegate,
                enhanceMetricsLMean = enhancement?.metrics?.lMean,
                enhanceMetricsPDark = enhancement?.metrics?.pDark,
                enhanceMetricsBSharpness = enhancement?.metrics?.bSharpness,
                enhanceMetricsNNoise = enhancement?.metrics?.nNoise,
                locationHiddenBySystem = false,
                updatedAt = now,
            )
            Timber.tag("Queue").i(
                UploadLog.message(
                    category = CATEGORY_ENQUEUE_OK,
                    action = "enqueue_updated",
                    photoId = photo.id,
                    uri = uri,
                    state = UploadItemState.QUEUED,
                    details = arrayOf(
                        "queue_item_id" to existing.id,
                        "size" to resolvedSize,
                        "display_name" to displayName,
                        "existing" to true,
                    ),
                ),
            )
        }
    }

    suspend fun findStoredContentSha256(uri: Uri): String? = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        val photo = photoDao.getByUri(uriString)
        val photoDigest = photo?.sha256?.takeIf { it.isNotBlank() }
        if (photoDigest != null) {
            return@withContext photoDigest
        }
        val existing = uploadItemDao.getByUri(uriString)
        existing?.idempotencyKey?.let(::contentSha256FromIdempotencyKey)
    }

    suspend fun computeAndStoreContentSha256(uri: Uri): String = withContext(Dispatchers.IO) {
        val digest = Hashing.sha256(contentResolver, uri)
        val uriString = uri.toString()
        val photo = photoDao.getByUri(uriString)
        if (photo != null && photo.sha256 != digest) {
            runCatching { photoDao.upsert(photo.copy(sha256 = digest)) }.onFailure { error ->
                Timber.tag("Queue").w(error, "Failed to persist photo hash for %s", uri)
            }
        }
        digest
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
                category = CATEGORY_STATE,
                action = "mark_queued",
                photoId = photo.id,
                uri = uri,
                state = UploadItemState.QUEUED,
                details = arrayOf(
                    "queue_item_id" to existing.id,
                ),
            ),
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
                category = CATEGORY_CANCEL,
                action = "mark_cancelled",
                photoId = photo.id,
                uri = uri,
                state = UploadItemState.FAILED,
                details = arrayOf(
                    "queue_item_id" to existing.id,
                    "was_processing" to wasProcessing,
                ),
            ),
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
                category = CATEGORY_CANCEL_ALL,
                action = "cancel_all",
                state = UploadItemState.FAILED,
            ),
        )
    }

    suspend fun recoverStuckProcessing(): Int = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        val updatedBefore = now - STUCK_TIMEOUT_MS
        recoverStuckProcessingInternal(now, updatedBefore)
    }

    suspend fun recoverStuckProcessing(updatedBefore: Long): Int = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        recoverStuckProcessingInternal(now, updatedBefore)
    }

    suspend fun fetchQueued(limit: Int, recoverStuck: Boolean = true): List<UploadQueueItem> = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        if (recoverStuck) {
            val updatedBefore = now - STUCK_TIMEOUT_MS
            recoverStuckProcessingInternal(now, updatedBefore)
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
                        lastErrorMessage = null,
                        updatedAt = updateTimestamp,
                    )
                    Timber.tag("Queue").w(
                        UploadLog.message(
                            category = CATEGORY_FETCH,
                            action = "skip_missing_uri",
                            state = UploadItemState.FAILED,
                            details = arrayOf(
                                "queue_item_id" to entity.id,
                                "reason" to "missing_uri",
                            ),
                        ),
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
                        lastErrorMessage = null,
                        updatedAt = updateTimestamp,
                    )
                    Timber.tag("Queue").w(
                        UploadLog.message(
                            category = CATEGORY_FETCH,
                            action = "skip_invalid_uri",
                            state = UploadItemState.FAILED,
                            details = arrayOf(
                                "queue_item_id" to entity.id,
                                "reason" to "invalid_uri",
                            ),
                        ),
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
                        state = UploadItemState.QUEUED,
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt,
                        lastErrorKind = UploadErrorKind.fromRawValue(entity.lastErrorKind),
                        lastErrorHttpCode = entity.httpCode,
                        lastErrorMessage = entity.lastErrorMessage,
                    )
                )
                Timber.tag("Queue").i(
                    UploadLog.message(
                        category = CATEGORY_FETCH,
                        action = "item",
                        photoId = entity.photoId,
                        uri = uri,
                        state = UploadItemState.QUEUED,
                        details = arrayOf(
                            "queue_item_id" to entity.id,
                            "display_name" to displayName,
                            "size" to entity.size,
                        ),
                    ),
                )
            }
        }
        Timber.tag("Queue").i(
            UploadLog.message(
                category = CATEGORY_FETCH,
                action = "summary",
                state = UploadItemState.QUEUED,
                details = arrayOf(
                    "requested" to limit,
                    "returned" to items.size,
                ),
            ),
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
                category = CATEGORY_STATE,
                action = action,
                state = if (success) UploadItemState.PROCESSING else null,
                details = arrayOf(
                    "queue_item_id" to id,
                ),
            ),
        )
        success
    }

    suspend fun markAccepted(id: Long, uploadId: String?) = withContext(Dispatchers.IO) {
        uploadItemDao.updateState(
            id = id,
            state = UploadItemState.SUCCEEDED.rawValue,
            updatedAt = currentTimeMillis(),
        )
        Timber.tag("Queue").i(
            UploadLog.message(
                category = CATEGORY_STATE,
                action = "mark_accepted",
                state = UploadItemState.SUCCEEDED,
                details = buildList {
                    add("queue_item_id" to id)
                    uploadId?.takeIf { it.isNotBlank() }?.let { add("upload_id" to it) }
                }.toTypedArray(),
            ),
        )
        notifySuccess(
            id = id,
            uploadId = uploadId,
            trigger = UploadSuccessTrigger.ACCEPTED,
        )
    }

    suspend fun markSucceeded(id: Long) = withContext(Dispatchers.IO) {
        uploadItemDao.updateState(
            id = id,
            state = UploadItemState.SUCCEEDED.rawValue,
            updatedAt = currentTimeMillis(),
        )
        Timber.tag("Queue").i(
            UploadLog.message(
                category = CATEGORY_STATE,
                action = "mark_succeeded",
                state = UploadItemState.SUCCEEDED,
                details = arrayOf(
                    "queue_item_id" to id,
                ),
            ),
        )
        notifySuccess(
            id = id,
            uploadId = null,
            trigger = UploadSuccessTrigger.SUCCEEDED,
        )
    }

    private suspend fun notifySuccess(
        id: Long,
        uploadId: String?,
        trigger: UploadSuccessTrigger,
    ) {
        if (successListeners.isEmpty()) {
            return
        }
        val entity = runCatching { uploadItemDao.getById(id) }.getOrElse { error ->
            Timber.tag("Queue").w(
                error,
                "Failed to load queue item for success listeners: queue_item_id=%d",
                id,
            )
            return
        } ?: run {
            Timber.tag("Queue").w(
                "Upload success listeners skipped: queue_item_id=%d missing",
                id,
            )
            return
        }
        val displayName = entity.displayName.takeIf { it.isNotBlank() } ?: DEFAULT_DISPLAY_NAME
        val event = UploadSuccessEvent(
            itemId = id,
            photoId = entity.photoId,
            contentUri = entity.uri.toUriOrNull(),
            displayName = displayName,
            sizeBytes = entity.size.takeIf { it > 0 },
            trigger = trigger,
            uploadId = uploadId,
        )
        for (listener in successListeners) {
            try {
                listener.onUploadSucceeded(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Timber.tag("Queue").w(
                    error,
                    "Upload success listener failed: queue_item_id=%d, trigger=%s",
                    id,
                    trigger.name,
                )
            }
        }
    }

    suspend fun findSourceForItem(id: Long): UploadSourceInfo? = withContext(Dispatchers.IO) {
        val entity = uploadItemDao.getById(id) ?: return@withContext null
        val photo = photoDao.getById(entity.photoId) ?: return@withContext null
        val uri = photo.uri.toUriOrNull() ?: return@withContext null
        UploadSourceInfo(
            photoId = photo.id,
            uri = uri,
            sizeBytes = photo.size.takeIf { it > 0 },
        )
    }

    suspend fun updateProcessingHeartbeat(id: Long) = withContext(Dispatchers.IO) {
        val updatedRows = uploadItemDao.touchProcessing(
            id = id,
            processingState = UploadItemState.PROCESSING.rawValue,
            updatedAt = currentTimeMillis(),
        )
        if (updatedRows > 0) {
            Timber.tag("Queue").v(
                UploadLog.message(
                    category = CATEGORY_HEARTBEAT,
                    action = "processing",
                    state = UploadItemState.PROCESSING,
                    details = arrayOf(
                        "queue_item_id" to id,
                    ),
                ),
            )
        }
    }

    suspend fun markFailed(
        id: Long,
        errorKind: UploadErrorKind,
        httpCode: Int? = null,
        requeue: Boolean = false,
        errorMessage: String? = null,
    ) = withContext(Dispatchers.IO) {
        val state = if (requeue) UploadItemState.QUEUED else UploadItemState.FAILED
        uploadItemDao.updateStateWithError(
            id = id,
            state = state.rawValue,
            lastErrorKind = errorKind.rawValue,
            httpCode = httpCode,
            lastErrorMessage = errorMessage,
            updatedAt = currentTimeMillis(),
        )
        Timber.tag("Queue").w(
            UploadLog.message(
                category = CATEGORY_STATE,
                action = "mark_failed",
                state = state,
                details = buildList {
                    add("queue_item_id" to id)
                    add("error_kind" to errorKind)
                    add("http_code" to httpCode)
                    add("requeue" to requeue)
                    if (!errorMessage.isNullOrBlank()) {
                        add("error_message" to errorMessage)
                    }
                }.toTypedArray(),
            ),
        )
    }

    suspend fun hasQueued(): Boolean = withContext(Dispatchers.IO) {
        uploadItemDao.countByState(UploadItemState.QUEUED.rawValue) > 0
    }

    suspend fun getQueueStats(): UploadQueueStats = withContext(Dispatchers.IO) {
        val succeededCutoff = currentTimeMillis() - SUCCEEDED_RETENTION_MS
        UploadQueueStats(
            queued = uploadItemDao.countByState(UploadItemState.QUEUED.rawValue),
            processing = uploadItemDao.countByState(UploadItemState.PROCESSING.rawValue),
            succeeded = uploadItemDao.countByStateUpdatedAfter(
                state = UploadItemState.SUCCEEDED.rawValue,
                updatedAfter = succeededCutoff,
            ),
            failed = uploadItemDao.countByState(UploadItemState.FAILED.rawValue),
        )
    }

    suspend fun getState(id: Long): UploadItemState? = withContext(Dispatchers.IO) {
        val entity = uploadItemDao.getById(id) ?: return@withContext null
        UploadItemState.fromRawValue(entity.state)
    }

    suspend fun setLocationHiddenBySystem(
        id: Long,
        hidden: Boolean,
    ) = withContext(Dispatchers.IO) {
        uploadItemDao.updateLocationHiddenBySystem(
            id = id,
            hidden = hidden,
            updatedAt = currentTimeMillis(),
        )
    }

    suspend fun requeueAllProcessing(): Int = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        val requeued = uploadItemDao.requeueAllProcessingToQueued(
            processingState = UploadItemState.PROCESSING.rawValue,
            queuedState = UploadItemState.QUEUED.rawValue,
            updatedAt = now,
        )
        if (requeued > 0) {
            Timber.tag("Queue").i(
                UploadLog.message(
                    category = CATEGORY_REQUEUE,
                    action = "processing_signal",
                    state = UploadItemState.QUEUED,
                    details = arrayOf(
                        "requeued" to requeued,
                    ),
                ),
            )
        }
        requeued
    }

    private fun buildDisplayName(relPath: String?, uri: Uri): String {
        val fromRelPath = relPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val fromUri = uri.lastPathSegment?.takeIf { it.isNotBlank() }
        return fromRelPath ?: fromUri ?: DEFAULT_DISPLAY_NAME
    }

    private suspend fun createFallbackPhoto(
        uri: Uri,
        uriString: String,
        contentSha256: String?
    ): PhotoEntity {
        val metadata = metadataReader.read(uri)
        val resolvedSha256 = contentSha256 ?: Hashing.sha256(contentResolver, uri)
        val entity = PhotoEntity(
            id = uriString,
            uri = uriString,
            relPath = buildRelPath(metadata, uri),
            sha256 = resolvedSha256,
            takenAt = resolveTakenAt(metadata),
            size = metadata?.size ?: 0L,
            mime = metadata?.mimeType ?: DEFAULT_MIME,
        )
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

    private suspend fun buildIdempotencyKey(photoId: String): String {
        val photo = photoDao.getById(photoId)
            ?: throw IllegalStateException("Photo not found for id $photoId")
        return buildIdempotencyKey(photo)
    }

    private fun buildIdempotencyKey(photo: PhotoEntity): String {
        val digest = photo.sha256.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing digest for photo ${photo.id}")
        return idempotencyKeyFromContentSha256(digest)
    }

    private fun currentTimeMillis(): Long = clock.instant().toEpochMilli()

    private fun UploadItemEntity.entityIdempotencyKey(): String? {
        return idempotencyKey.takeIf { it.isNotBlank() }
    }

    private suspend fun recoverStuckProcessingInternal(now: Long, updatedBefore: Long): Int {
        val requeued = uploadItemDao.requeueProcessingToQueued(
            processingState = UploadItemState.PROCESSING.rawValue,
            queuedState = UploadItemState.QUEUED.rawValue,
            updatedAt = now,
            updatedBefore = updatedBefore,
        )
        if (requeued > 0) {
            Timber.tag("Queue").w(
                UploadLog.message(
                    category = CATEGORY_RECOVER,
                    action = "processing",
                    state = UploadItemState.QUEUED,
                    details = arrayOf(
                        "requeued" to requeued,
                    ),
                ),
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
        const val STUCK_TIMEOUT_MS: Long = 5 * 60 * 1_000L
        private const val CATEGORY_ENQUEUE_REQUEST = "QUEUE/ENQUEUE_REQUEST"
        private const val CATEGORY_ENQUEUE_OK = "QUEUE/ENQUEUE_OK"
        private const val CATEGORY_STATE = "QUEUE/STATE"
        private const val CATEGORY_CANCEL = "QUEUE/CANCEL"
        private const val CATEGORY_CANCEL_ALL = "QUEUE/CANCEL_ALL"
        private const val CATEGORY_FETCH = "QUEUE/FETCH"
        private const val CATEGORY_HEARTBEAT = "QUEUE/HEARTBEAT"
        private const val CATEGORY_REQUEUE = "QUEUE/REQUEUE"
        private const val CATEGORY_RECOVER = "QUEUE/RECOVER"
        const val SUCCEEDED_RETENTION_MS: Long = 24 * 60 * 60 * 1_000L
    }
}

data class UploadQueueEntry(
    val entity: UploadItemEntity,
    val uri: Uri?,
    val state: UploadItemState,
    val lastErrorKind: UploadErrorKind?,
    val lastErrorHttpCode: Int?,
    val lastErrorMessage: String? = null,
    val locationHiddenBySystem: Boolean = false,
)

private fun UploadItemEntity.isExpiredSucceeded(cutoff: Long): Boolean {
    if (state != UploadItemState.SUCCEEDED.rawValue) return false
    val effectiveUpdatedAt = updatedAt ?: createdAt
    return effectiveUpdatedAt < cutoff
}

data class UploadQueueStats(
    val queued: Int,
    val processing: Int,
    val succeeded: Int,
    val failed: Int,
)

data class UploadQueueItem(
    val id: Long,
    val uri: Uri,
    val idempotencyKey: String,
    val displayName: String,
    val size: Long,
    val state: UploadItemState = UploadItemState.QUEUED,
    val createdAt: Long = 0,
    val updatedAt: Long? = null,
    val lastErrorKind: UploadErrorKind? = null,
    val lastErrorHttpCode: Int? = null,
    val lastErrorMessage: String? = null,
)

data class UploadSourceInfo(
    val photoId: String,
    val uri: Uri,
    val sizeBytes: Long?,
)
