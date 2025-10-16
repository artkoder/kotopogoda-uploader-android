package com.kotopogoda.uploader.core.data.upload

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadItemDao {

    @Query("SELECT * FROM upload_items ORDER BY created_at ASC")
    fun observeAll(): Flow<List<UploadItemEntity>>

    @Query("SELECT * FROM upload_items WHERE unique_name = :uniqueName LIMIT 1")
    suspend fun findByUniqueName(uniqueName: String): UploadItemEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: UploadItemEntity): Long

    @Update
    suspend fun update(entity: UploadItemEntity)

    @Query(
        "UPDATE upload_items SET state = :state, error_kind = NULL, error_http_code = NULL, updated_at = :timestamp WHERE unique_name = :uniqueName"
    )
    suspend fun updateStateClearingError(uniqueName: String, state: String, timestamp: Long)

    @Query(
        "UPDATE upload_items SET state = :state, error_kind = NULL, error_http_code = NULL, updated_at = :timestamp"
    )
    suspend fun updateAllStatesClearingError(state: String, timestamp: Long)
}
