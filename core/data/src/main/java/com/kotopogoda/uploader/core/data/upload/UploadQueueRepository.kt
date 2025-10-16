package com.kotopogoda.uploader.core.data.upload

import android.net.Uri
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class UploadQueueRepository @Inject constructor(
    private val uploadItemDao: UploadItemDao
) {

    fun observeQueue(): Flow<List<UploadItem>> =
        uploadItemDao.observeAll().map { entities ->
            entities.map { it.toDomain() }
        }

    suspend fun upsert(items: List<UploadItem>) {
        if (items.isEmpty()) return
        uploadItemDao.upsert(items.map { it.toEntity() })
    }

    suspend fun getQueued(): List<UploadItem> =
        uploadItemDao.getByState(UploadItemState.QUEUED.rawValue).map { it.toDomain() }

    suspend fun updateState(ids: List<Long>, state: UploadItemState, updatedAt: Instant = Instant.now()) {
        if (ids.isEmpty()) return
        uploadItemDao.updateState(
            ids = ids,
            state = state.rawValue,
            updatedAt = updatedAt.toEpochMilli()
        )
    }

    suspend fun updateError(
        id: Long,
        errorKind: UploadItemErrorKind?,
        httpCode: Int?,
        updatedAt: Instant = Instant.now()
    ) {
        uploadItemDao.updateError(
            id = id,
            errorKind = errorKind?.rawValue,
            httpCode = httpCode,
            updatedAt = updatedAt.toEpochMilli()
        )
    }
}

private fun UploadItemEntity.toDomain(): UploadItem = UploadItem(
    id = id,
    uri = Uri.parse(uri),
    displayName = displayName,
    size = size,
    state = UploadItemState.fromRawValue(state),
    lastErrorKind = UploadItemErrorKind.fromRawValue(lastErrorKind),
    httpCode = httpCode,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt)
)

private fun UploadItem.toEntity(): UploadItemEntity = UploadItemEntity(
    id = id,
    uri = uri.toString(),
    displayName = displayName,
    size = size,
    state = state.rawValue,
    lastErrorKind = lastErrorKind?.rawValue,
    httpCode = httpCode,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)
