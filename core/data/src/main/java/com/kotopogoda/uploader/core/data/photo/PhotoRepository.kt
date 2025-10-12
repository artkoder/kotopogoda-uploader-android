package com.kotopogoda.uploader.core.data.photo

import android.net.Uri
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class PhotoRepository @Inject constructor(
    private val photoDao: PhotoDao
) {
    fun observePhotos(): Flow<List<PhotoItem>> =
        photoDao.observeAllJpeg().map { entities ->
            entities.map { entity -> entity.toPhotoItem() }
        }

    suspend fun findIndexAtOrAfter(date: Instant): Int = withContext(Dispatchers.IO) {
        val total = photoDao.countAll()
        if (total == 0) {
            return@withContext 0
        }
        val before = photoDao.countBefore(date.toEpochMilli())
        before.coerceAtMost(total - 1)
    }

    suspend fun countAll(): Int = withContext(Dispatchers.IO) {
        photoDao.countAll()
    }
}

private fun PhotoEntity.toPhotoItem(): PhotoItem =
    PhotoItem(
        id = id,
        uri = Uri.parse(uri),
        takenAt = takenAt?.let(Instant::ofEpochMilli)
    )
