package com.kotopogoda.uploader.core.data.folder

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folder LIMIT 1")
    fun observeFolder(): Flow<FolderEntity?>

    @Query("SELECT * FROM folder LIMIT 1")
    suspend fun getFolder(): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity): Long

    @Query("DELETE FROM folder")
    suspend fun clear()
}
