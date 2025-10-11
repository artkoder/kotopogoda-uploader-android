package com.kotopogoda.uploader.core.data.photo

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class PhotoRepository @Inject constructor(
    private val photoDao: PhotoDao
) {
    fun observePhotos(): Flow<List<PhotoItem>> =
        photoDao.observeAllJpeg().map { entities ->
            entities.map { entity -> entity.toPhotoItem() }
        }
}

private fun PhotoEntity.toPhotoItem(): PhotoItem =
    PhotoItem(
        id = id,
        uri = Uri.parse(uri),
        exifDate = exifDate
    )
