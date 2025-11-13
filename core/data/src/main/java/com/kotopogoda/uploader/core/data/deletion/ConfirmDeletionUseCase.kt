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
import timber.log.Timber

class ConfirmDeletionUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
    private val deletionQueueRepository: DeletionQueueRepository,
    private val deleteRequestFactory: MediaStoreDeleteRequestFactory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun prepare(chunkSize: Int = DEFAULT_CHUNK_SIZE): PrepareResult = withContext(ioDispatcher) {
        val requiredPermissions = requiredPermissionsFor(Build.VERSION.SDK_INT)
        val missingPermissions = requiredPermissions.filterNot { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            return@withContext PrepareResult.PermissionRequired(missingPermissions.toSet())
        }

        val pendingItems = deletionQueueRepository.getPending()
        if (pendingItems.isEmpty()) {
            return@withContext PrepareResult.NoPending
        }

        val batchItems = buildBatchItems(pendingItems)
        if (batchItems.isEmpty()) {
            return@withContext PrepareResult.NoPending
        }

        if (isAtLeastR()) {
            val batches = batchItems
                .chunked(chunkSize.coerceAtLeast(1))
                .mapIndexed { index, chunk ->
                    val pendingIntent = deleteRequestFactory.create(contentResolver, chunk.map { it.uri })
                    DeleteBatch(
                        id = UUID.randomUUID().toString(),
                        index = index,
                        items = chunk,
                        intentSender = pendingIntent.intentSender,
                        requiresRetryAfterApproval = false,
                    )
                }
            return@withContext PrepareResult.Ready(batches = batches, initialOutcome = Outcome())
        }

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
                pendingBatches += DeleteBatch(
                    id = UUID.randomUUID().toString(),
                    index = index,
                    items = listOf(item),
                    intentSender = intentSender,
                    requiresRetryAfterApproval = true,
                )
                Timber.tag(TAG).i(
                    error,
                    "Требуется подтверждение пользователя для удаления %s",
                    item.uri
                )
            } catch (security: SecurityException) {
                Timber.tag(TAG).w(security, "Отказано в доступе при удалении %s", item.uri)
                failures += item
            } catch (throwable: Throwable) {
                Timber.tag(TAG).w(throwable, "Не удалось удалить %s", item.uri)
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
            return@withContext PrepareResult.NoPending
        }

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
            Timber.tag(TAG).i("Пользователь отменил подтверждение удаления для батча %s", batch.id)
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
                    Timber.tag(TAG).w(security, "Повторное удаление %s завершилось с ошибкой", item.uri)
                    -1
                } catch (throwable: Throwable) {
                    Timber.tag(TAG).w(throwable, "Повторное удаление %s завершилось исключением", item.uri)
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

        BatchProcessingResult.Completed(outcome)
    }

    private suspend fun buildBatchItems(items: List<DeletionItem>): List<BatchItem> = withContext(ioDispatcher) {
        items.mapNotNull { item ->
            val uri = runCatching { Uri.parse(item.contentUri) }.getOrNull()
            if (uri == null) {
                Timber.tag(TAG).w("Некорректный URI: %s", item.contentUri)
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
            Timber.tag(TAG).w(security, "Нет доступа к размеру файла %s", uri)
            null
        } catch (throwable: Throwable) {
            Timber.tag(TAG).w(throwable, "Не удалось получить размер файла %s", uri)
            null
        }
    }

    private fun isMissingFromStore(uri: Uri): Boolean {
        return try {
            contentResolver.query(uri, ID_PROJECTION, null, null, null)?.use { cursor ->
                !cursor.moveToFirst()
            } ?: true
        } catch (security: SecurityException) {
            Timber.tag(TAG).w(security, "Нет доступа при проверке удаления %s", uri)
            false
        } catch (throwable: Throwable) {
            Timber.tag(TAG).w(throwable, "Ошибка при проверке удаления %s", uri)
            false
        }
    }

    private suspend fun applyRepositoryUpdates(
        successes: List<BatchItem>,
        failures: List<BatchItem>,
        skipped: List<BatchItem>,
    ) {
        if (successes.isNotEmpty()) {
            deletionQueueRepository.markConfirmed(successes.map { it.item.mediaId })
        }
        if (failures.isNotEmpty()) {
            deletionQueueRepository.markFailed(failures.map { it.item.mediaId }, CAUSE_FAILURE)
        }
        if (skipped.isNotEmpty()) {
            deletionQueueRepository.markSkipped(skipped.map { it.item.mediaId })
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
        private const val TAG = "ConfirmDeletion"
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
