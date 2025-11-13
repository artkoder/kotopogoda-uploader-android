package com.kotopogoda.uploader.core.data.deletion

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeletionItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(items: List<DeletionItem>)

    @Query(
        "SELECT * FROM deletion_queue WHERE status = :pendingStatus AND is_uploading = 0 ORDER BY created_at ASC"
    )
    fun observePending(pendingStatus: String = DeletionItemStatus.PENDING): Flow<List<DeletionItem>>

    @Query(
        "SELECT * FROM deletion_queue WHERE status = :pendingStatus AND is_uploading = 0 ORDER BY created_at ASC"
    )
    suspend fun getPending(pendingStatus: String = DeletionItemStatus.PENDING): List<DeletionItem>

    @Query("UPDATE deletion_queue SET status = :status, is_uploading = 0 WHERE media_id IN (:ids)")
    suspend fun updateStatus(ids: List<Long>, status: String): Int

    @Query(
        "UPDATE deletion_queue SET is_uploading = :uploading WHERE media_id IN (:ids) AND status = :pendingStatus"
    )
    suspend fun updateUploading(
        ids: List<Long>,
        uploading: Boolean,
        pendingStatus: String = DeletionItemStatus.PENDING
    ): Int

    @Query(
        "DELETE FROM deletion_queue WHERE status IN (:statuses) AND created_at < :olderThan"
    )
    suspend fun purge(statuses: List<String>, olderThan: Long): Int

    @Query("SELECT * FROM deletion_queue WHERE media_id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<DeletionItem>

    @Query("SELECT * FROM deletion_queue")
    suspend fun getAll(): List<DeletionItem>
}
