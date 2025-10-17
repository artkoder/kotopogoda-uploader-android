package com.kotopogoda.uploader.core.data.photo

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

data class MediaStorePhotoMetadata(
    val displayName: String?,
    val size: Long?,
    val mimeType: String?,
    val dateAddedMillis: Long?,
    val dateModifiedMillis: Long?,
    val dateTakenMillis: Long?,
    val relativePath: String?,
)

@Singleton
class MediaStorePhotoMetadataReader @Inject constructor(
    private val contentResolver: ContentResolver,
) {

    fun read(uri: Uri): MediaStorePhotoMetadata? {
        return runCatching {
            contentResolver.query(
                uri,
                PROJECTION,
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return null
                }
                MediaStorePhotoMetadata(
                    displayName = cursor.readString(MediaStore.MediaColumns.DISPLAY_NAME),
                    size = cursor.readLong(MediaStore.MediaColumns.SIZE)?.takeIf { it >= 0 },
                    mimeType = cursor.readString(MediaStore.MediaColumns.MIME_TYPE),
                    dateAddedMillis = cursor.readLong(MediaStore.MediaColumns.DATE_ADDED)?.toMillis(),
                    dateModifiedMillis = cursor.readLong(MediaStore.MediaColumns.DATE_MODIFIED)?.toMillis(),
                    dateTakenMillis = cursor.readLong(MediaStore.Images.Media.DATE_TAKEN)?.takeIf { it > 0 },
                    relativePath = cursor.readString(MediaStore.Images.Media.RELATIVE_PATH),
                )
            }
        }.onFailure { error ->
            Timber.w(error, "Failed to read metadata for %s", uri)
        }.getOrNull()
    }

    private fun Cursor.readString(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) {
            return null
        }
        return getString(index)
    }

    private fun Cursor.readLong(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) {
            return null
        }
        return getLong(index)
    }

    private fun Long.toMillis(): Long? {
        if (this <= 0) return null
        return this * 1000
    }

    companion object {
        private val PROJECTION = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
    }
}
