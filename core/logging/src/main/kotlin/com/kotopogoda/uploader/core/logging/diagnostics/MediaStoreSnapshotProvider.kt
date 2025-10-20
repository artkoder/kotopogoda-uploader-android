package com.kotopogoda.uploader.core.logging.diagnostics

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaStoreItemSnapshot(
    val uri: String,
    val displayName: String?,
    val size: Long?,
    val mimeType: String?,
    val dateAddedMillis: Long?,
    val dateModifiedMillis: Long?,
    val dateTakenMillis: Long?,
    val relativePath: String?,
)

interface MediaStoreSnapshotProvider {
    suspend fun getRecent(limit: Int): List<MediaStoreItemSnapshot>
}

@Singleton
class ContentResolverMediaStoreSnapshotProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaStoreSnapshotProvider {

    override suspend fun getRecent(limit: Int): List<MediaStoreItemSnapshot> = withContext(ioDispatcher) {
        if (limit <= 0) return@withContext emptyList()
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val results = mutableListOf<MediaStoreItemSnapshot>()
        resolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext() && results.size < limit) {
                val id = cursor.getLong(idIndex)
                val uri = ContentUris.withAppendedId(collection, id)
                results += MediaStoreItemSnapshot(
                    uri = uri.toString(),
                    displayName = cursor.readString(MediaStore.MediaColumns.DISPLAY_NAME),
                    size = cursor.readLong(MediaStore.MediaColumns.SIZE)?.takeIf { it >= 0 },
                    mimeType = cursor.readString(MediaStore.MediaColumns.MIME_TYPE),
                    dateAddedMillis = cursor.readLong(MediaStore.MediaColumns.DATE_ADDED)?.toMillis(),
                    dateModifiedMillis = cursor.readLong(MediaStore.MediaColumns.DATE_MODIFIED)?.toMillis(),
                    dateTakenMillis = cursor.readLong(MediaStore.Images.Media.DATE_TAKEN)?.takeIf { it > 0 },
                    relativePath = cursor.readString(MediaStore.Images.Media.RELATIVE_PATH),
                )
            }
        }
        results
    }

    private fun Cursor.readString(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getString(index)
    }

    private fun Cursor.readLong(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getLong(index)
    }

    private fun Long.toMillis(): Long? {
        if (this <= 0) return null
        return this * 1000
    }
}
