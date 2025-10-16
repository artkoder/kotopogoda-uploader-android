package com.kotopogoda.uploader.core.data.upload

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class UploadItemsRepository @Inject constructor(
    private val dao: UploadItemDao,
) {

    fun observeAll(): Flow<List<UploadItemEntity>> = dao.observeAll()

    suspend fun upsertPending(
        uniqueName: String,
        uri: Uri,
        idempotencyKey: String,
        displayName: String,
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.findByUniqueName(uniqueName)
        if (existing == null) {
            dao.insert(
                UploadItemEntity(
                    uniqueName = uniqueName,
                    uri = uri.toString(),
                    idempotencyKey = idempotencyKey,
                    displayName = displayName,
                    state = UploadItemState.PENDING.rawValue,
                    errorKind = null,
                    errorHttpCode = null,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else {
            dao.update(
                existing.copy(
                    uri = uri.toString(),
                    idempotencyKey = idempotencyKey,
                    displayName = displayName,
                    state = UploadItemState.PENDING.rawValue,
                    errorKind = null,
                    errorHttpCode = null,
                    updatedAt = now,
                )
            )
        }
    }

    suspend fun markPending(uniqueName: String) {
        dao.updateStateClearingError(uniqueName, UploadItemState.PENDING.rawValue, System.currentTimeMillis())
    }

    suspend fun markCancelled(uniqueName: String) {
        dao.updateStateClearingError(uniqueName, UploadItemState.CANCELLED.rawValue, System.currentTimeMillis())
    }

    suspend fun markAllCancelled() {
        dao.updateAllStatesClearingError(UploadItemState.CANCELLED.rawValue, System.currentTimeMillis())
    }
}
