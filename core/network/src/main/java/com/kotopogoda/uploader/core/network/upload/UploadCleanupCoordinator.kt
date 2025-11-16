package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import com.kotopogoda.uploader.core.data.deletion.DeletionQueueRepository
import com.kotopogoda.uploader.core.data.deletion.DeletionRequest
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.data.upload.UploadSourceInfo
import com.kotopogoda.uploader.core.logging.structuredLog
import com.kotopogoda.uploader.core.settings.SettingsRepository
import java.util.ArrayDeque
import java.util.LinkedHashSet
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
        val initialMediaId = uploadUri.extractMediaId()
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
            logAutoDeletionQueueEvent(
                stage = "settings",
                mediaId = initialMediaId,
                uri = uploadUri,
                displayName = displayName,
                reason = DELETION_REASON_UPLOADED_CLEANUP,
                settingEnabled = false,
                alreadyEnqueued = false,
                outcome = REASON_SETTINGS_ERROR,
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
            logAutoDeletionQueueEvent(
                stage = "settings",
                mediaId = initialMediaId,
                uri = uploadUri,
                displayName = displayName,
                reason = DELETION_REASON_UPLOADED_CLEANUP,
                settingEnabled = false,
                alreadyEnqueued = false,
                outcome = REASON_SETTINGS_DISABLED,
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
            logAutoDeletionQueueEvent(
                stage = "dedupe",
                mediaId = initialMediaId,
                uri = uploadUri,
                displayName = displayName,
                reason = DELETION_REASON_UPLOADED_CLEANUP,
                settingEnabled = true,
                alreadyEnqueued = true,
                outcome = REASON_ALREADY_PROCESSED,
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
            logAutoDeletionQueueEvent(
                stage = "source_lookup",
                mediaId = initialMediaId,
                uri = uploadUri,
                displayName = displayName,
                reason = DELETION_REASON_UPLOADED_CLEANUP,
                settingEnabled = true,
                alreadyEnqueued = false,
                outcome = REASON_SOURCE_LOOKUP_FAILED,
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
            logAutoDeletionQueueEvent(
                stage = "resolve_uri",
                mediaId = initialMediaId,
                uri = uploadUri,
                displayName = displayName,
                reason = DELETION_REASON_UPLOADED_CLEANUP,
                settingEnabled = true,
                alreadyEnqueued = false,
                outcome = REASON_MISSING_CONTENT_URI,
            )
            return CleanupResult.Skipped(SkipReason.MISSING_CONTENT_URI)
        }

        val resolvedMediaId = resolveMediaId(
            initialMediaId = initialMediaId,
            sourceInfo = sourceInfo,
            contentUri = contentUri,
        )
        if (resolvedMediaId == null) {
            logCleanupDecision(
                itemId = itemId,
                mediaId = null,
                uri = contentUri,
                success = false,
                reason = REASON_MISSING_MEDIA_ID,
            )
            logAutoDeletionQueueEvent(
                stage = "resolve_media_id",
                mediaId = null,
                uri = contentUri,
                displayName = displayName,
                reason = DELETION_REASON_UPLOADED_CLEANUP,
                settingEnabled = true,
                alreadyEnqueued = false,
                outcome = REASON_MISSING_MEDIA_ID,
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
            logAutoDeletionQueueEvent(
                stage = "enqueue",
                mediaId = resolvedMediaId,
                uri = contentUri,
                displayName = displayName,
                reason = DELETION_REASON_UPLOADED_CLEANUP,
                settingEnabled = true,
                alreadyEnqueued = false,
                outcome = REASON_ENQUEUE_ERROR,
                sizeBytes = sizeBytes,
                throwable = error,
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
            logAutoDeletionQueueEvent(
                stage = "enqueue",
                mediaId = resolvedMediaId,
                uri = contentUri,
                displayName = displayName,
                reason = DELETION_REASON_UPLOADED_CLEANUP,
                settingEnabled = true,
                alreadyEnqueued = false,
                outcome = REASON_ENQUEUED,
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
            logAutoDeletionQueueEvent(
                stage = "enqueue",
                mediaId = resolvedMediaId,
                uri = contentUri,
                displayName = displayName,
                reason = DELETION_REASON_UPLOADED_CLEANUP,
                settingEnabled = true,
                alreadyEnqueued = true,
                outcome = REASON_ENQUEUE_DUPLICATE,
                sizeBytes = sizeBytes,
            )
            CleanupResult.Skipped(SkipReason.ENQUEUE_DUPLICATE)
        }
    }

    private fun resolveMediaId(
        initialMediaId: Long?,
        sourceInfo: UploadSourceInfo?,
        contentUri: Uri,
    ): Long? {
        if (initialMediaId != null) {
            return initialMediaId
        }
        val sourceId = sourceInfo?.photoId.extractMediaIdCandidate()
        if (sourceId != null) {
            return sourceId
        }
        val sourceUriId = sourceInfo?.uri.extractMediaId()
        if (sourceUriId != null) {
            return sourceUriId
        }
        return contentUri.extractMediaId()
    }

    private fun String?.extractMediaIdCandidate(): Long? {
        if (this.isNullOrBlank()) return null
        return extractMediaIdFromRaw(this)
    }

    private fun Uri?.extractMediaId(): Long? {
        if (this == null) return null
        return extractMediaIdFromRaw(lastPathSegment) ?: extractMediaIdFromRaw(toString())
    }

    private fun extractMediaIdFromRaw(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val decoded = Uri.decode(raw)
        decoded.toLongOrNull()?.let { return it }
        decoded.substringAfterLast(':', "").takeIf { it.isNotEmpty() }?.toLongOrNull()?.let { return it }
        decoded.substringAfterLast('/', "").takeIf { it.isNotEmpty() }?.toLongOrNull()?.let { return it }
        return null
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

    private fun logAutoDeletionQueueEvent(
        stage: String,
        mediaId: Long?,
        uri: Uri?,
        displayName: String?,
        reason: String,
        settingEnabled: Boolean,
        alreadyEnqueued: Boolean,
        outcome: String,
        sizeBytes: Long? = null,
        throwable: Throwable? = null,
    ) {
        val attributes = mutableListOf(
            "phase" to "enqueue",
            "source" to "uploaded_cleanup",
            "stage" to stage,
            "reason" to reason,
            "already_enqueued" to alreadyEnqueued,
            "setting_enabled" to settingEnabled,
            "outcome" to outcome,
        )
        mediaId?.let { attributes += "media_id" to it }
        uri?.let { attributes += "uri" to it.toString() }
        displayName?.let { attributes += "display_name" to it }
        sizeBytes?.let { attributes += "size_bytes" to it }
        val message = structuredLog(*attributes.toTypedArray())
        when {
            throwable != null -> Timber.tag(DELETION_QUEUE_TAG).e(throwable, message)
            outcome == REASON_ALREADY_PROCESSED || outcome == REASON_ENQUEUE_DUPLICATE ->
                Timber.tag(DELETION_QUEUE_TAG).w(message)
            else -> Timber.tag(DELETION_QUEUE_TAG).i(message)
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
        private const val DELETION_QUEUE_TAG = "DeletionQueue"
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
