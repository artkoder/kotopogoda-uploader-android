package com.kotopogoda.uploader.core.data.upload

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadItemDao {

    @Query("SELECT * FROM upload_items WHERE photo_id = :photoId LIMIT 1")
    suspend fun getByPhotoId(photoId: String): UploadItemEntity?

    @Query("SELECT * FROM upload_items WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): UploadItemEntity?

    @Insert
    suspend fun insert(entity: UploadItemEntity): Long

    @Query("SELECT * FROM upload_items WHERE state = :state ORDER BY created_at LIMIT :limit")
    suspend fun getByState(state: String, limit: Int): List<UploadItemEntity>

    @Query("SELECT * FROM upload_items ORDER BY created_at DESC")
    fun observeAll(): Flow<List<UploadItemEntity>>

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM upload_items WHERE photo_id = :photoId AND state IN (:queuedState, :processingState)" +
            ")"
    )
    fun observeQueuedOrProcessingByPhotoId(
        photoId: String,
        queuedState: String,
        processingState: String,
    ): Flow<Boolean>

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM upload_items WHERE uri = :uri AND state IN (:queuedState, :processingState)" +
            ")"
    )
    fun observeQueuedOrProcessingByUri(
        uri: String,
        queuedState: String,
        processingState: String,
    ): Flow<Boolean>

    @Query("SELECT * FROM upload_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UploadItemEntity?

    @Query(
        "UPDATE upload_items SET state = :state, uri = :uri, display_name = :displayName, size = :size, " +
            "idempotency_key = :idempotencyKey, last_error_kind = NULL, http_code = NULL, updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateStateWithMetadata(
        id: Long,
        state: String,
        uri: String,
        displayName: String,
        size: Long,
        idempotencyKey: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE upload_items SET state = :state, last_error_kind = NULL, http_code = NULL, updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateState(id: Long, state: String, updatedAt: Long)

    @Query(
        "UPDATE upload_items SET updated_at = :updatedAt " +
            "WHERE id = :id AND state = :processingState"
    )
    suspend fun touchProcessing(
        id: Long,
        processingState: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE upload_items SET state = :newState, updated_at = :updatedAt " +
            "WHERE id = :id AND state = :expectedState"
    )
    suspend fun updateStateIfCurrent(
        id: Long,
        expectedState: String,
        newState: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE upload_items SET state = :state, last_error_kind = :lastErrorKind, http_code = :httpCode, updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateStateWithError(
        id: Long,
        state: String,
        lastErrorKind: String?,
        httpCode: Int?,
        updatedAt: Long,
    )

    @Query("SELECT COUNT(*) FROM upload_items WHERE state = :state")
    suspend fun countByState(state: String): Int

    @Query(
        "UPDATE upload_items SET state = :state, last_error_kind = NULL, http_code = NULL, updated_at = :updatedAt WHERE state IN (:states)"
    )
    suspend fun updateStatesClearingError(states: List<String>, state: String, updatedAt: Long)

    @Query(
        "UPDATE upload_items SET state = :queuedState, last_error_kind = NULL, http_code = NULL, updated_at = :updatedAt " +
            "WHERE state = :processingState AND updated_at <= :updatedBefore"
    )
    suspend fun requeueProcessingToQueued(
        processingState: String,
        queuedState: String,
        updatedAt: Long,
        updatedBefore: Long,
    ): Int

    @Query(
        "UPDATE upload_items SET state = :queuedState, last_error_kind = NULL, http_code = NULL, updated_at = :updatedAt " +
            "WHERE state = :processingState"
    )
    suspend fun requeueAllProcessingToQueued(
        processingState: String,
        queuedState: String,
        updatedAt: Long,
    ): Int
}
