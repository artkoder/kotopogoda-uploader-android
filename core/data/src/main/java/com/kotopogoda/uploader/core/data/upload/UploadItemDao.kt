package com.kotopogoda.uploader.core.data.upload

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadItemDao {
    @Upsert
    suspend fun upsert(items: List<UploadItemEntity>)

    @Query("SELECT * FROM upload_items WHERE state = :state ORDER BY created_at ASC")
    suspend fun getByState(state: String): List<UploadItemEntity>

    @Query("SELECT * FROM upload_items ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<UploadItemEntity>>

    @Query("UPDATE upload_items SET state = :state, updated_at = :updatedAt WHERE id IN (:ids)")
    suspend fun updateState(ids: List<Long>, state: String, updatedAt: Long)

    @Query(
        "UPDATE upload_items " +
            "SET last_error_kind = :errorKind, http_code = :httpCode, updated_at = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateError(id: Long, errorKind: String?, httpCode: Int?, updatedAt: Long)
}
