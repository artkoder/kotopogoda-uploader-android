package com.kotopogoda.uploader.core.data.deletion

import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class DeletionQueueRepository @Inject constructor(
    private val deletionItemDao: DeletionItemDao,
    private val clock: Clock,
) {

    fun observePending(): Flow<List<DeletionItem>> {
        return deletionItemDao.observePending()
            .map { items ->
                items.sortedBy { it.createdAt }
            }
            .distinctUntilChanged()
    }

    suspend fun getPending(): List<DeletionItem> = withContext(Dispatchers.IO) {
        deletionItemDao.getPending()
    }

    suspend fun enqueue(requests: List<DeletionRequest>) = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) {
            return@withContext
        }
        val baseTime = clock.millis()
        val prepared = requests.mapIndexed { index, request ->
            DeletionItem(
                mediaId = request.mediaId,
                contentUri = request.contentUri,
                displayName = request.displayName,
                sizeBytes = request.sizeBytes,
                dateTaken = request.dateTaken,
                reason = request.reason,
                status = DeletionItemStatus.PENDING,
                isUploading = false,
                createdAt = baseTime + index,
            )
        }
        deletionItemDao.enqueue(prepared)
        Timber.tag(TAG).i("В очередь удаления добавлено %d элементов", prepared.size)
    }

    suspend fun markConfirmed(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) {
            return@withContext 0
        }
        val updated = deletionItemDao.updateStatus(ids, DeletionItemStatus.CONFIRMED)
        if (updated > 0) {
            Timber.tag(TAG).i("Подтверждено удаление %d элементов", updated)
        }
        updated
    }

    suspend fun markFailed(ids: List<Long>, cause: String?): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) {
            return@withContext 0
        }
        val updated = deletionItemDao.updateStatus(ids, DeletionItemStatus.FAILED)
        if (updated > 0) {
            Timber.tag(TAG).w(
                "Удаление %d элементов завершилось с ошибкой: %s",
                updated,
                cause ?: "unknown"
            )
        }
        updated
    }

    suspend fun markSkipped(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) {
            return@withContext 0
        }
        val updated = deletionItemDao.updateStatus(ids, DeletionItemStatus.SKIPPED)
        if (updated > 0) {
            Timber.tag(TAG).i("Пропущено удаление %d элементов", updated)
        }
        updated
    }

    suspend fun markUploading(ids: List<Long>, uploading: Boolean): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) {
            return@withContext 0
        }
        val updated = deletionItemDao.updateUploading(ids, uploading)
        if (updated > 0) {
            Timber.tag(TAG).i(
                "Статус загрузки для %d элементов: %s",
                updated,
                if (uploading) "uploading" else "idle"
            )
        }
        updated
    }

    suspend fun purge(olderThan: Long = clock.millis() - DEFAULT_RETENTION_MS): Int = withContext(Dispatchers.IO) {
        val threshold = olderThan
        val removed = deletionItemDao.purge(TERMINAL_STATUSES, threshold)
        if (removed > 0) {
            Timber.tag(TAG).i("Удалено %d записей из истории очереди", removed)
        }
        removed
    }

    companion object {
        private const val TAG = "DeletionQueue"
        private val DEFAULT_RETENTION_MS = Duration.ofDays(7).toMillis()
        private val TERMINAL_STATUSES = listOf(
            DeletionItemStatus.CONFIRMED,
            DeletionItemStatus.SKIPPED,
        )
    }
}
