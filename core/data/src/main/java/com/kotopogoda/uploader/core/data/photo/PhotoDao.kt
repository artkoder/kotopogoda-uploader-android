package com.kotopogoda.uploader.core.data.photo

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Upsert
    suspend fun upsert(photo: PhotoEntity)

    @Upsert
    suspend fun upsertAll(photos: List<PhotoEntity>)

    @Query("SELECT * FROM photos WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PhotoEntity?

    @Query("SELECT * FROM photos WHERE sha256 = :sha LIMIT 1")
    suspend fun getBySha256(sha: String): PhotoEntity?

    @Query(
        "SELECT (strftime('%s', datetime(exif_date / 1000, 'unixepoch'), 'start of month') * 1000) AS monthStartEpochMillis, " +
            "COUNT(*) AS count FROM photos GROUP BY monthStartEpochMillis ORDER BY monthStartEpochMillis DESC"
    )
    fun observeMonthlyCounts(): Flow<List<PhotoCountByMonth>>

    @Query(
        "SELECT (strftime('%s', datetime(exif_date / 1000, 'unixepoch'), 'start of day') * 1000) AS dayStartEpochMillis, " +
            "COUNT(*) AS count FROM photos GROUP BY dayStartEpochMillis ORDER BY dayStartEpochMillis DESC"
    )
    fun observeDailyCounts(): Flow<List<PhotoCountByDay>>
}
