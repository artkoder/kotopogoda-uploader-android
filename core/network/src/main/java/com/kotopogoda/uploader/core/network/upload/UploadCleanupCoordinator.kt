package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import com.kotopogoda.uploader.core.data.deletion.DeletionQueueRepository
import com.kotopogoda.uploader.core.data.deletion.DeletionRequest
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.settings.SettingsRepository
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import timber.log.Timber

@Singleton
class UploadCleanupCoordinator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val deletionQueueRepository: DeletionQueueRepository,
    private val uploadQueueRepository: UploadQueueRepository,
) {

    private val handledItemIds = LinkedHashSet<Long>()
    private val handledOrder = ArrayDeque<Long>()

    suspend fun onUploadSucceeded(
        itemId: Long,
        uploadUri: Uri?,
        displayName: String,
        reportedSizeBytes: Long?,
        httpCode: Int?,
        successKind: String,
    ): CleanupResult {
        val initialMediaId = uploadUri?.lastPathSegment?.toLongOrNull()
        logSuccessDetected(
            itemId = itemId,
            mediaId = initialMediaId,
            httpCode = httpCode,
            successKind = successKind,
            uri = uploadUri,
        )

        val settings = runCatching { settingsRepository.flow.first() }.getOrElse { error ->
            logCleanupDecision(
                itemId = itemId,
                mediaId = initialMediaId,
                uri = uploadUri,
                success = false,
                reason = REASON_SETTINGS_ERROR,
                throwable = error,
            )
            return CleanupResult.Failure(SkipReason.SETTINGS_ERROR, error)
        }

        if (!settings.autoDeleteAfterUpload) {
            logCleanupDecision(
                itemId = itemId,
                mediaId = initialMediaId,
                uri = uploadUri,
                success = false,
                reason = REASON_SETTINGS_DISABLED,
            )
            return CleanupResult.Skipped(SkipReason.SETTINGS_DISABLED)
        }

        if (isHandled(itemId)) {
            logCleanupDecision(
                itemId = itemId,
                mediaId = initialMediaId,
                uri = uploadUri,
                success = false,
                reason = REASON_ALREADY_PROCESSED,
            )
            return CleanupResult.Skipped(SkipReason.ALREADY_PROCESSED)
        }

        val sourceInfo = runCatching { uploadQueueRepository.findSourceForItem(itemId) }.getOrElse { error ->
            logCleanupDecision(
                itemId = itemId,
                mediaId = initialMediaId,
                uri = uploadUri,
                success = false,
                reason = REASON_SOURCE_LOOKUP_FAILED,
                throwable = error,
            )
            return CleanupResult.Failure(SkipReason.SOURCE_LOOKUP_FAILED, error)
        }

        val contentUri = sourceInfo?.uri ?: uploadUri
        if (contentUri == null) {
            logCleanupDecision(
                itemId = itemId,
                mediaId = initialMediaId,
                uri = uploadUri,
                success = false,
                reason = REASON_MISSING_CONTENT_URI,
            )
            return CleanupResult.Skipped(SkipReason.MISSING_CONTENT_URI)
        }

        val resolvedMediaId = initialMediaId
            ?: sourceInfo?.photoId?.toLongOrNull()
            ?: contentUri.lastPathSegment?.toLongOrNull()
        if (resolvedMediaId == null) {
            logCleanupDecision(
                itemId = itemId,
                mediaId = null,
                uri = contentUri,
                success = false,
                reason = REASON_MISSING_MEDIA_ID,
            )
            return CleanupResult.Skipped(SkipReason.MISSING_MEDIA_ID)
        }

        val sizeBytes = reportedSizeBytes ?: sourceInfo?.sizeBytes
        val request = DeletionRequest(
            mediaId = resolvedMediaId,
            contentUri = contentUri.toString(),
            displayName = displayName,
            sizeBytes = sizeBytes,
            dateTaken = null,
            reason = DELETION_REASON_UPLOADED_CLEANUP,
        )

        val inserted = runCatching { deletionQueueRepository.enqueue(listOf(request)) }.getOrElse { error ->
            logCleanupDecision(
                itemId = itemId,
                mediaId = resolvedMediaId,
                uri = contentUri,
                success = false,
                reason = REASON_ENQUEUE_ERROR,
                throwable = error,
                sizeBytes = sizeBytes,
            )
            return CleanupResult.Failure(SkipReason.ENQUEUE_ERROR, error)
        }

        return if (inserted > 0) {
            markHandled(itemId)
            logCleanupDecision(
                itemId = itemId,
                mediaId = resolvedMediaId,
                uri = contentUri,
                success = true,
                reason = REASON_ENQUEUED,
                sizeBytes = sizeBytes,
            )
            CleanupResult.Success(resolvedMediaId, inserted)
        } else {
            markHandled(itemId)
            logCleanupDecision(
                itemId = itemId,
                mediaId = resolvedMediaId,
                uri = contentUri,
                success = false,
                reason = REASON_ENQUEUE_DUPLICATE,
                sizeBytes = sizeBytes,
            )
            CleanupResult.Skipped(SkipReason.ENQUEUE_DUPLICATE)
        }
    }

    private fun isHandled(itemId: Long): Boolean = synchronized(handledItemIds) {
        handledItemIds.contains(itemId)
    }

    private fun markHandled(itemId: Long) {
        synchronized(handledItemIds) {
            if (handledItemIds.add(itemId)) {
                handledOrder.addLast(itemId)
                if (handledOrder.size > MAX_TRACKED_ITEMS) {
                    val removed = handledOrder.removeFirst()
                    handledItemIds.remove(removed)
                }
            }
        }
    }

    private fun logSuccessDetected(
        itemId: Long,
        mediaId: Long?,
        httpCode: Int?,
        successKind: String,
        uri: Uri?,
    ) {
        val details = buildList {
            add("queue_item_id" to itemId)
            mediaId?.let { add("media_id" to it) }
            httpCode?.let { add("http_code" to it) }
            add("kind" to successKind)
        }
        val message = UploadLog.message(
            category = CATEGORY_CLEANUP,
            action = ACTION_SUCCESS_DETECTED,
            uri = uri,
            details = details.toTypedArray(),
        )
        Timber.tag(LOG_TAG).i(message)
    }

    private fun logCleanupDecision(
        itemId: Long,
        mediaId: Long?,
        uri: Uri?,
        success: Boolean,
        reason: String,
        throwable: Throwable? = null,
        sizeBytes: Long? = null,
    ) {
        val details = buildList {
            add("queue_item_id" to itemId)
            mediaId?.let { add("media_id" to it) }
            add("success" to success)
            add("reason" to reason)
            sizeBytes?.let { add("size_bytes" to it) }
        }
        val message = UploadLog.message(
            category = CATEGORY_CLEANUP,
            action = ACTION_ENQUEUED_FOR_CLEANUP,
            uri = uri,
            details = details.toTypedArray(),
        )
        when {
            throwable != null -> Timber.tag(LOG_TAG).e(throwable, message)
            success -> Timber.tag(LOG_TAG).i(message)
            else -> Timber.tag(LOG_TAG).w(message)
        }
    }

    sealed class CleanupResult {
        data class Success(val mediaId: Long, val enqueuedCount: Int) : CleanupResult()
        data class Skipped(val reason: SkipReason) : CleanupResult()
        data class Failure(val reason: SkipReason, val error: Throwable) : CleanupResult()
    }

    enum class SkipReason {
        SETTINGS_DISABLED,
        ALREADY_PROCESSED,
        SOURCE_LOOKUP_FAILED,
        MISSING_CONTENT_URI,
        MISSING_MEDIA_ID,
        ENQUEUE_DUPLICATE,
        ENQUEUE_ERROR,
        SETTINGS_ERROR,
    }

    companion object {
        private const val LOG_TAG = "WorkManager"
        private const val CATEGORY_CLEANUP = "UPLOAD/CLEANUP"
        private const val ACTION_SUCCESS_DETECTED = "upload_success_detected"
        private const val ACTION_ENQUEUED_FOR_CLEANUP = "enqueued_for_cleanup"
        private const val DELETION_REASON_UPLOADED_CLEANUP = "uploaded_cleanup"
        private const val REASON_SETTINGS_DISABLED = "setting_disabled"
        private const val REASON_ALREADY_PROCESSED = "already_enqueued"
        private const val REASON_SOURCE_LOOKUP_FAILED = "source_lookup_failed"
        private const val REASON_MISSING_CONTENT_URI = "missing_content_uri"
        private const val REASON_MISSING_MEDIA_ID = "missing_media_id"
        private const val REASON_ENQUEUED = "enqueued"
        private const val REASON_ENQUEUE_DUPLICATE = "duplicate"
        private const val REASON_ENQUEUE_ERROR = "enqueue_error"
        private const val REASON_SETTINGS_ERROR = "settings_error"
        private const val MAX_TRACKED_ITEMS = 512
    }
}
