package com.kotopogoda.uploader.core.data.deletion

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kotopogoda.uploader.core.logging.structuredLog
import timber.log.Timber

class ConfirmDeletionUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
    private val deletionQueueRepository: DeletionQueueRepository,
    private val deleteRequestFactory: MediaStoreDeleteRequestFactory,
    private val deletionAnalytics: DeletionAnalytics,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun prepare(chunkSize: Int = DEFAULT_CHUNK_SIZE): PrepareResult = withContext(ioDispatcher) {
        val requiredPermissions = requiredPermissionsFor(Build.VERSION.SDK_INT)
        val missingPermissions = requiredPermissions.filterNot { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            val attributes = mutableListOf<Pair<String, Any?>>(
                "phase" to "confirm_prepare",
                "event" to "permission_required",
                "missing_count" to missingPermissions.size,
            )
            Timber.tag(TAG).i(structuredLog(*attributes.toTypedArray()))
            return@withContext PrepareResult.PermissionRequired(missingPermissions.toSet())
        }

        val pendingItems = deletionQueueRepository.getPending()
        val totalPending = pendingItems.size
        if (totalPending == 0) {
            Timber.tag(TAG).i(structuredLog("phase" to "confirm_prepare", "event" to "no_pending"))
            return@withContext PrepareResult.NoPending
        }

        val batchItems = buildBatchItems(pendingItems)
        val validCount = batchItems.size
        val invalidCount = totalPending - validCount
        if (validCount == 0) {
            val attributes = mutableListOf<Pair<String, Any?>>(
                "phase" to "confirm_prepare",
                "event" to "no_valid_items",
                "pending_total" to totalPending,
            )
            if (invalidCount > 0) {
                attributes += "pending_invalid" to invalidCount
            }
            Timber.tag(TAG).w(structuredLog(*attributes.toTypedArray()))
            return@withContext PrepareResult.NoPending
        }

        val normalizedChunkSize = chunkSize.coerceAtLeast(1)
        if (isAtLeastR()) {
            val chunks = batchItems.chunked(normalizedChunkSize)
            val chunkTotal = chunks.size
            if (chunkTotal == 0) {
                val attributes = mutableListOf<Pair<String, Any?>>(
                    "phase" to "confirm_prepare",
                    "event" to "no_pending",
                    "pending_total" to validCount,
                )
                if (invalidCount > 0) {
                    attributes += "pending_invalid" to invalidCount
                }
                Timber.tag(TAG).i(structuredLog(*attributes.toTypedArray()))
                return@withContext PrepareResult.NoPending
            }
            val batches = chunks.mapIndexed { index, chunk ->
                val batchId = UUID.randomUUID().toString()
                val attributes = mutableListOf<Pair<String, Any?>>(
                    "phase" to "confirm_prepare",
                    "event" to "batch_created",
                    "batch_id" to batchId,
                    "chunk_index" to index + 1,
                    "chunk_total" to chunkTotal,
                    "chunk_size" to chunk.size,
                    "pending_total" to validCount,
                )
                if (invalidCount > 0) {
                    attributes += "pending_invalid" to invalidCount
                }
                Timber.tag(TAG).i(structuredLog(*attributes.toTypedArray()))
                val pendingIntent = deleteRequestFactory.create(contentResolver, chunk.map { it.uri })
                DeleteBatch(
                    id = batchId,
                    index = index,
                    items = chunk,
                    intentSender = IntentSenderWrapper(pendingIntent.intentSender),
                    requiresRetryAfterApproval = false,
                )
            }
            val summaryAttributes = mutableListOf<Pair<String, Any?>>(
                "phase" to "confirm_prepare",
                "event" to "batches_ready",
                "batch_total" to chunkTotal,
                "pending_total" to validCount,
            )
            if (invalidCount > 0) {
                summaryAttributes += "pending_invalid" to invalidCount
            }
            Timber.tag(TAG).i(structuredLog(*summaryAttributes.toTypedArray()))
            return@withContext PrepareResult.Ready(batches = batches, initialOutcome = Outcome())
        }

        val summaryAttributes = mutableListOf<Pair<String, Any?>>(
            "phase" to "confirm_prepare",
            "event" to "legacy_execute",
            "pending_total" to validCount,
        )
        if (invalidCount > 0) {
            summaryAttributes += "pending_invalid" to invalidCount
        }
        Timber.tag(TAG).i(structuredLog(*summaryAttributes.toTypedArray()))

        val successes = mutableListOf<BatchItem>()
        val failures = mutableListOf<BatchItem>()
        val skipped = mutableListOf<BatchItem>()
        val pendingBatches = mutableListOf<DeleteBatch>()

        batchItems.forEachIndexed { index, item ->
            try {
                val deletedCount = contentResolver.delete(item.uri, null, null)
                when {
                    deletedCount > 0 -> successes += item
                    deletedCount == 0 -> skipped += item
                    else -> failures += item
                }
            } catch (error: RecoverableSecurityException) {
                val intentSender = error.userAction.actionIntent.intentSender
                val batchId = UUID.randomUUID().toString()
                pendingBatches += DeleteBatch(
                    id = batchId,
                    index = index,
                    items = listOf(item),
                    intentSender = IntentSenderWrapper(intentSender),
                    requiresRetryAfterApproval = true,
                )
                val attributes = mutableListOf<Pair<String, Any?>>(
                    "phase" to "confirm_prepare",
                    "event" to "permission_prompt",
                    "batch_id" to batchId,
                    "item_index" to index,
                    "pending_total" to validCount,
                    "item_uri" to item.uri.toString(),
                )
                Timber.tag(TAG).i(error, structuredLog(*attributes.toTypedArray()))
            } catch (security: SecurityException) {
                val attributes = mutableListOf<Pair<String, Any?>>(
                    "phase" to "confirm_prepare",
                    "event" to "delete_security_error",
                    "item_index" to index,
                    "item_uri" to item.uri.toString(),
                )
                Timber.tag(TAG).w(security, structuredLog(*attributes.toTypedArray()))
                failures += item
            } catch (throwable: Throwable) {
                val attributes = mutableListOf<Pair<String, Any?>>(
                    "phase" to "confirm_prepare",
                    "event" to "delete_error",
                    "item_index" to index,
                    "item_uri" to item.uri.toString(),
                )
                Timber.tag(TAG).w(throwable, structuredLog(*attributes.toTypedArray()))
                failures += item
            }
        }

        applyRepositoryUpdates(successes, failures, skipped)

        val initialOutcome = Outcome(
            confirmedCount = successes.size,
            failedCount = failures.size,
            skippedCount = skipped.size,
            freedBytes = successes.sumOf { it.resolvedSize ?: 0L }
        )

        if (pendingBatches.isEmpty() && !initialOutcome.hasChanges) {
            Timber.tag(TAG).i(
                structuredLog(
                    "phase" to "confirm_prepare",
                    "event" to "no_pending_after_updates",
                    "pending_total" to validCount,
                    "confirmed" to successes.size,
                    "failed" to failures.size,
                    "skipped" to skipped.size,
                )
            )
            return@withContext PrepareResult.NoPending
        }

        Timber.tag(TAG).i(
            structuredLog(
                "phase" to "confirm_prepare",
                "event" to "ready_for_confirmation",
                "pending_total" to validCount,
                "batches_pending" to pendingBatches.size,
                "confirmed_initial" to successes.size,
                "failed_initial" to failures.size,
                "skipped_initial" to skipped.size,
                "freed_bytes_initial" to initialOutcome.freedBytes,
            )
        )

        PrepareResult.Ready(
            batches = pendingBatches,
            initialOutcome = initialOutcome,
        )
    }

    suspend fun handleBatchResult(
        batch: DeleteBatch,
        resultCode: Int,
        data: Intent?,
    ): BatchProcessingResult = withContext(ioDispatcher) {
        if (resultCode != Activity.RESULT_OK) {
            Timber.tag(TAG).i(
                structuredLog(
                    "phase" to "confirm_result",
                    "event" to "cancelled",
                    "batch_id" to batch.id,
                    "result_code" to resultCode,
                )
            )
            deletionAnalytics.deletionCancelled(batch.id)
            return@withContext BatchProcessingResult.Cancelled
        }

        val successes = mutableListOf<BatchItem>()
        val failures = mutableListOf<BatchItem>()
        val skipped = mutableListOf<BatchItem>()

        if (batch.requiresRetryAfterApproval) {
            batch.items.forEach { item ->
                val deletedCount = try {
                    contentResolver.delete(item.uri, null, null)
                } catch (security: SecurityException) {
                    Timber.tag(TAG).w(
                        security,
                        structuredLog(
                            "phase" to "confirm_result",
                            "event" to "retry_delete_security_error",
                            "batch_id" to batch.id,
                            "item_uri" to item.uri.toString(),
                        )
                    )
                    -1
                } catch (throwable: Throwable) {
                    Timber.tag(TAG).w(
                        throwable,
                        structuredLog(
                            "phase" to "confirm_result",
                            "event" to "retry_delete_error",
                            "batch_id" to batch.id,
                            "item_uri" to item.uri.toString(),
                        )
                    )
                    -1
                }
                when {
                    deletedCount > 0 -> successes += item
                    deletedCount == 0 -> skipped += item
                    else -> failures += item
                }
            }
        } else {
            batch.items.forEach { item ->
                val missing = isMissingFromStore(item.uri)
                if (missing) {
                    successes += item
                } else {
                    failures += item
                }
            }
        }

        applyRepositoryUpdates(successes, failures, skipped)

        val outcome = Outcome(
            confirmedCount = successes.size,
            failedCount = failures.size,
            skippedCount = skipped.size,
            freedBytes = successes.sumOf { it.resolvedSize ?: 0L }
        )

        Timber.tag(TAG).i(
            structuredLog(
                "phase" to "confirm_result",
                "event" to "batch_completed",
                "batch_id" to batch.id,
                "result_code" to resultCode,
                "confirmed" to outcome.confirmedCount,
                "failed" to outcome.failedCount,
                "skipped" to outcome.skippedCount,
                "freed_bytes" to outcome.freedBytes,
                "requires_retry" to batch.requiresRetryAfterApproval,
            )
        )

        BatchProcessingResult.Completed(outcome)
    }

    suspend fun reconcilePending(): Int = withContext(ioDispatcher) {
        val pendingItems = deletionQueueRepository.getPending()
        val totalPending = pendingItems.size
        if (totalPending == 0) {
            Timber.tag(TAG).i(structuredLog("phase" to "reconcile", "event" to "no_pending"))
            return@withContext 0
        }

        val batchItems = buildBatchItems(pendingItems)
        val validCount = batchItems.size
        val invalidCount = totalPending - validCount
        if (validCount == 0) {
            val attributes = mutableListOf<Pair<String, Any?>>(
                "phase" to "reconcile",
                "event" to "no_valid_items",
                "pending_total" to totalPending,
            )
            if (invalidCount > 0) {
                attributes += "pending_invalid" to invalidCount
            }
            Timber.tag(TAG).w(structuredLog(*attributes.toTypedArray()))
            return@withContext 0
        }

        val confirmed = batchItems.filter { isMissingFromStore(it.uri) }
        val remaining = validCount - confirmed.size
        if (confirmed.isEmpty()) {
            val attributes = mutableListOf<Pair<String, Any?>>(
                "phase" to "reconcile",
                "event" to "no_changes",
                "pending_total" to validCount,
                "failures" to remaining,
            )
            if (invalidCount > 0) {
                attributes += "pending_invalid" to invalidCount
            }
            Timber.tag(TAG).i(structuredLog(*attributes.toTypedArray()))
            return@withContext 0
        }

        val freedBytes = confirmed.sumOf { it.resolvedSize ?: 0L }
        val attributes = mutableListOf<Pair<String, Any?>>(
            "phase" to "reconcile",
            "event" to "confirmed_by_reconcile",
            "pending_total" to validCount,
            "confirmed" to confirmed.size,
            "failures" to remaining,
            "freed_bytes" to freedBytes,
        )
        if (invalidCount > 0) {
            attributes += "pending_invalid" to invalidCount
        }
        Timber.tag(TAG).i(structuredLog(*attributes.toTypedArray()))

        applyRepositoryUpdates(
            successes = confirmed,
            failures = emptyList(),
            skipped = emptyList(),
        )

        confirmed.size
    }

    private suspend fun buildBatchItems(items: List<DeletionItem>): List<BatchItem> = withContext(ioDispatcher) {
        items.mapNotNull { item ->
            val uri = runCatching { Uri.parse(item.contentUri) }.getOrNull()
            if (uri == null) {
                Timber.tag(TAG).w(
                    structuredLog(
                        "phase" to "prepare_items",
                        "event" to "invalid_uri",
                        "content_uri" to item.contentUri,
                    )
                )
                return@mapNotNull null
            }
            val resolvedSize = resolveSize(uri, item.sizeBytes)
            BatchItem(
                item = item,
                uri = uri,
                resolvedSize = resolvedSize,
            )
        }
    }

    private fun resolveSize(uri: Uri, originalSize: Long?): Long? {
        if (originalSize != null && originalSize > 0) {
            return originalSize
        }
        return try {
            contentResolver.query(uri, SIZE_PROJECTION, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getLong(0)
                } else {
                    null
                }
            }
        } catch (security: SecurityException) {
            Timber.tag(TAG).w(
                security,
                structuredLog(
                    "phase" to "resolve_size",
                    "event" to "security_error",
                    "uri" to uri.toString(),
                )
            )
            null
        } catch (throwable: Throwable) {
            Timber.tag(TAG).w(
                throwable,
                structuredLog(
                    "phase" to "resolve_size",
                    "event" to "error",
                    "uri" to uri.toString(),
                )
            )
            null
        }
    }

    private fun isMissingFromStore(uri: Uri): Boolean {
        return try {
            contentResolver.query(uri, ID_PROJECTION, null, null, null)?.use { cursor ->
                !cursor.moveToFirst()
            } ?: true
        } catch (security: SecurityException) {
            Timber.tag(TAG).w(
                security,
                structuredLog(
                    "phase" to "reconcile",
                    "event" to "check_security_error",
                    "uri" to uri.toString(),
                )
            )
            false
        } catch (throwable: Throwable) {
            Timber.tag(TAG).w(
                throwable,
                structuredLog(
                    "phase" to "reconcile",
                    "event" to "check_error",
                    "uri" to uri.toString(),
                )
            )
            false
        }
    }

    private suspend fun applyRepositoryUpdates(
        successes: List<BatchItem>,
        failures: List<BatchItem>,
        skipped: List<BatchItem>,
    ) {
        var freedBytes = 0L
        if (successes.isNotEmpty()) {
            deletionQueueRepository.markConfirmed(successes.map { it.item.mediaId })
            freedBytes = successes.sumOf { it.resolvedSize ?: 0L }
            deletionAnalytics.deletionConfirmed(successes.size, freedBytes)
        }
        if (failures.isNotEmpty()) {
            deletionQueueRepository.markFailed(failures.map { it.item.mediaId }, CAUSE_FAILURE)
        }
        if (skipped.isNotEmpty()) {
            deletionQueueRepository.markSkipped(skipped.map { it.item.mediaId })
        }
        if (successes.isNotEmpty() || failures.isNotEmpty() || skipped.isNotEmpty()) {
            val pendingAfter = deletionQueueRepository.getPending().size
            Timber.tag(TAG).i(
                structuredLog(
                    "phase" to "repo_update",
                    "event" to "statuses_applied",
                    "confirmed" to successes.size,
                    "failed" to failures.size,
                    "skipped" to skipped.size,
                    "freed_bytes" to freedBytes,
                    "pending_after" to pendingAfter,
                )
            )
        }
    }

    private fun requiredPermissionsFor(apiLevel: Int): Set<String> {
        val readPermission = if (apiLevel >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return setOf(readPermission)
    }

    private fun isAtLeastR(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    data class BatchItem(
        val item: DeletionItem,
        val uri: Uri,
        val resolvedSize: Long?,
    )

    data class DeleteBatch(
        val id: String,
        val index: Int,
        val items: List<BatchItem>,
        val intentSender: IntentSenderWrapper,
        val requiresRetryAfterApproval: Boolean,
    )

    data class Outcome(
        val confirmedCount: Int = 0,
        val failedCount: Int = 0,
        val skippedCount: Int = 0,
        val freedBytes: Long = 0L,
    ) {
        val hasChanges: Boolean
            get() = confirmedCount > 0 || failedCount > 0 || skippedCount > 0

        operator fun plus(other: Outcome): Outcome = Outcome(
            confirmedCount = confirmedCount + other.confirmedCount,
            failedCount = failedCount + other.failedCount,
            skippedCount = skippedCount + other.skippedCount,
            freedBytes = freedBytes + other.freedBytes,
        )
    }

    sealed interface PrepareResult {
        object NoPending : PrepareResult
        data class PermissionRequired(val permissions: Set<String>) : PrepareResult
        data class Ready(val batches: List<DeleteBatch>, val initialOutcome: Outcome) : PrepareResult
    }

    sealed interface BatchProcessingResult {
        object Cancelled : BatchProcessingResult
        data class Completed(val outcome: Outcome) : BatchProcessingResult
    }

    companion object {
        private const val TAG = "DeletionQueue"
        private const val DEFAULT_CHUNK_SIZE = 200
        private const val CAUSE_FAILURE = "media_store_delete_failed"
        private val SIZE_PROJECTION = arrayOf(MediaStore.MediaColumns.SIZE)
        private val ID_PROJECTION = arrayOf(MediaStore.MediaColumns._ID)
    }
}

class MediaStoreDeleteRequestFactory @Inject constructor() {
    fun create(contentResolver: ContentResolver, uris: Collection<Uri>) =
        MediaStore.createDeleteRequest(contentResolver, uris)
}

class IntentSenderWrapper(val intentSender: android.content.IntentSender)
