package com.kotopogoda.uploader.core.data.upload

import androidx.room.Dao
import androidx.room.Query

@Dao
interface UploadItemDao {

    @Query("SELECT * FROM upload_items WHERE state = :state ORDER BY created_at LIMIT :limit")
    suspend fun getByState(state: String, limit: Int): List<UploadItemEntity>

    @Query(
        "UPDATE upload_items SET state = :state, last_error_kind = NULL, http_code = NULL, updated_at = :updatedAt WHERE id = :id"
    )
    suspend fun updateState(id: Long, state: String, updatedAt: Long)

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
}
