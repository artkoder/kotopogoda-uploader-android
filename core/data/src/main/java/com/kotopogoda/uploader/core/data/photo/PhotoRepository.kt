package com.kotopogoda.uploader.core.data.photo

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.os.bundleOf
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class PhotoRepository @Inject constructor(
    private val folderRepository: FolderRepository,
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val resolver: ContentResolver = context.contentResolver

    fun observePhotos(): Flow<PagingData<PhotoItem>> =
        folderRepository.observeFolder()
            .map { folder -> folder?.let(::buildQuerySpec) }
            .distinctUntilChanged()
            .flatMapLatest { spec ->
                if (spec == null) {
                    flowOf(PagingData.empty())
                } else {
                    Pager(
                        config = PagingConfig(
                            pageSize = DEFAULT_PAGE_SIZE,
                            prefetchDistance = DEFAULT_PREFETCH_DISTANCE,
                            enablePlaceholders = false
                        ),
                        pagingSourceFactory = {
                            MediaStorePhotoPagingSource(
                                contentResolver = resolver,
                                spec = spec
                            )
                        }
                    ).flow
                }
            }

    suspend fun countAll(): Int = withContext(ioDispatcher) {
        val folder = folderRepository.getFolder() ?: return@withContext 0
        val spec = buildQuerySpec(folder)
        queryCount(spec)
    }

    suspend fun findIndexAtOrAfter(date: Instant): Int = withContext(ioDispatcher) {
        val folder = folderRepository.getFolder() ?: return@withContext 0
        val spec = buildQuerySpec(folder)
        val targetMillis = date.toEpochMilli()
        val additionalSelection = "${MediaStore.Images.Media.DATE_TAKEN} > ?"
        val additionalArgs = arrayOf(targetMillis.toString())
        val newerCount = queryCount(spec, additionalSelection, additionalArgs)
        val total = queryCount(spec)
        if (total == 0) {
            0
        } else {
            newerCount.coerceIn(0, max(0, total - 1))
        }
    }

    suspend fun clampIndex(index: Int): Int = withContext(ioDispatcher) {
        val total = countAll()
        if (total == 0) {
            0
        } else {
            index.coerceIn(0, total - 1)
        }
    }

    private fun queryCount(
        spec: MediaStoreQuerySpec,
        extraSelection: String? = null,
        extraArgs: Array<String>? = null
    ): Int {
        val selectionParts = mutableListOf<String>().apply {
            addAll(spec.selectionParts)
            if (!extraSelection.isNullOrBlank()) {
                add(extraSelection)
            }
        }
        val args = mutableListOf<String>().apply {
            addAll(spec.selectionArgs)
            if (extraArgs != null) {
                addAll(extraArgs)
            }
        }
        val bundle = bundleOf().apply {
            if (selectionParts.isNotEmpty()) {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    selectionParts.joinToString(separator = " AND ")
                )
            }
            if (args.isNotEmpty()) {
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    args.toTypedArray()
                )
            }
        }
        return runCatching {
            resolver.query(spec.contentUri, arrayOf(MediaStore.Images.Media._ID), bundle, null)
                ?.use { cursor -> cursor.getCountSafely() }
        }.getOrNull() ?: 0
    }

    private fun Cursor.getCountSafely(): Int =
        runCatching { count }.getOrDefault(0)

    private fun buildQuerySpec(folder: Folder): MediaStoreQuerySpec {
        val treeUri = Uri.parse(folder.treeUri)
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val delimiterIndex = documentId.indexOf(':')
        val volume = if (delimiterIndex >= 0) {
            documentId.substring(0, delimiterIndex).ifBlank { MediaStore.VOLUME_EXTERNAL }
        } else {
            MediaStore.VOLUME_EXTERNAL
        }
        val relativePath = if (delimiterIndex >= 0 && delimiterIndex + 1 < documentId.length) {
            documentId.substring(delimiterIndex + 1)
        } else {
            ""
        }
        val normalizedPath = relativePath.trim('/').takeIf { it.isNotEmpty() }?.let { path ->
            if (path.endsWith('/')) path else "$path/"
        }
        val baseSelection = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        baseSelection += "${MediaStore.Images.Media.MIME_TYPE} LIKE 'image/%'"
        normalizedPath?.let { path ->
            baseSelection += "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs += "$path%"
        }
        val contentUri = MediaStore.Images.Media.getContentUri(volume)
        return MediaStoreQuerySpec(
            contentUri = contentUri,
            selectionParts = baseSelection,
            selectionArgs = selectionArgs
        )
    }

    private data class MediaStoreQuerySpec(
        val contentUri: Uri,
        val selectionParts: List<String>,
        val selectionArgs: List<String>
    )

    private class MediaStorePhotoPagingSource(
        private val contentResolver: ContentResolver,
        private val spec: MediaStoreQuerySpec
    ) : PagingSource<Int, PhotoItem>() {

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PhotoItem> {
            val offset = params.key ?: 0
            val limit = params.loadSize
            val selection = spec.selectionParts.joinToString(separator = " AND ").takeIf { it.isNotEmpty() }
            val args = spec.selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray()
            val bundle = Bundle().apply {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.Images.Media.DATE_TAKEN)
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                )
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                selection?.let {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it)
                }
                args?.let {
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it)
                }
            }

            val cursor = runCatching {
                contentResolver.query(
                    spec.contentUri,
                    PROJECTION,
                    bundle,
                    null
                )
            }.getOrElse { error ->
                return LoadResult.Error(error)
            } ?: return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)

            return cursor.use { result ->
                val idIndex = result.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateTakenIndex = result.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val mimeIndex = result.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val items = buildList {
                    while (result.moveToNext()) {
                        val id = result.getLong(idIndex)
                        val uri = ContentUris.withAppendedId(spec.contentUri, id)
                        val takenAt = if (result.isNull(dateTakenIndex)) {
                            null
                        } else {
                            val value = result.getLong(dateTakenIndex)
                            if (value > 0) Instant.ofEpochMilli(value) else null
                        }
                        val mime = if (result.isNull(mimeIndex)) null else result.getString(mimeIndex)
                        if (mime != null && mime.startsWith("image/")) {
                            add(
                                PhotoItem(
                                    id = id.toString(),
                                    uri = uri,
                                    takenAt = takenAt
                                )
                            )
                        }
                    }
                }
                val nextKey = if (items.size < limit) {
                    null
                } else {
                    offset + items.size
                }
                val prevKey = if (offset == 0) {
                    null
                } else {
                    max(0, offset - limit)
                }
                LoadResult.Page(
                    data = items,
                    prevKey = prevKey,
                    nextKey = nextKey
                )
            }
        }

        override fun getRefreshKey(state: PagingState<Int, PhotoItem>): Int? {
            val anchor = state.anchorPosition ?: return null
            val closestPage = state.closestPageToPosition(anchor) ?: return null
            return closestPage.prevKey?.let { it + state.config.pageSize }
                ?: closestPage.nextKey?.let { max(0, it - state.config.pageSize) }
        }

        companion object {
            private val PROJECTION = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE
            )
        }
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 60
        private const val DEFAULT_PREFETCH_DISTANCE = 30
    }
}
