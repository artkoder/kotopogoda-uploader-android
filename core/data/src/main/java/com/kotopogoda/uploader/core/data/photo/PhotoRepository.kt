package com.kotopogoda.uploader.core.data.photo

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
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
        val additionalSelection = "$SORT_KEY_EXPRESSION > ?"
        val additionalArgs = arrayOf(targetMillis.toString())
        val newerCount = queryCount(spec, additionalSelection, additionalArgs)
        val total = queryCount(spec)
        if (total == 0) {
            0
        } else {
            newerCount.coerceIn(0, max(0, total - 1))
        }
    }

    suspend fun findIndexAtOrAfter(start: Instant, endExclusive: Instant): Int? =
        withContext(ioDispatcher) {
            val folder = folderRepository.getFolder() ?: return@withContext null
            if (!endExclusive.isAfter(start)) {
                return@withContext null
            }
            val spec = buildQuerySpec(folder)
            val rangeStartMillis = start.toEpochMilli()
            val rangeEndMillis = endExclusive.toEpochMilli()
            val beforeCount = queryCount(
                spec,
                "$SORT_KEY_EXPRESSION >= ?",
                arrayOf(rangeEndMillis.toString())
            )
            val inRangeCount = queryCount(
                spec,
                "$SORT_KEY_EXPRESSION >= ? AND $SORT_KEY_EXPRESSION < ?",
                arrayOf(rangeStartMillis.toString(), rangeEndMillis.toString())
            )
            if (inRangeCount == 0) {
                null
            } else {
                beforeCount
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
        val selectionString = selectionParts.joinToString(separator = " AND ")
            .takeIf { it.isNotEmpty() }
        val selectionArgsArray = args.takeIf { it.isNotEmpty() }?.toTypedArray()
        val bundle = bundleOf().apply {
            selectionString?.let {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it)
            }
            selectionArgsArray?.let {
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it)
            }
        }
        return runCatching {
            resolver.queryWithFallback(
                uris = spec.contentUris,
                projection = arrayOf(MediaStore.Images.Media._ID),
                selection = selectionString,
                selectionArgs = selectionArgsArray,
                sortOrder = null,
                bundle = bundle
            ) { _, cursor ->
                cursor.getCountSafely()
            } ?: 0
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
        val mediaStoreVolume = toMediaStoreVolume(volume)
        val contentUris = buildList {
            val primary = runCatching {
                MediaStore.Images.Media.getContentUri(mediaStoreVolume)
            }.getOrNull()
            if (primary != null) {
                add(primary)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val aggregated = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                if (primary == null || aggregated != primary) {
                    add(aggregated)
                }
            } else if (primary == null) {
                add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            }
        }.ifEmpty { listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI) }
        return MediaStoreQuerySpec(
            contentUris = contentUris,
            selectionParts = baseSelection,
            selectionArgs = selectionArgs
        )
    }

    private data class MediaStoreQuerySpec(
        val contentUris: List<Uri>,
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
            val bundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        "$SORT_KEY_EXPRESSION DESC"
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
            } else {
                null
            }

            val legacySortOrder = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                buildString {
                    append("$SORT_KEY_EXPRESSION DESC")
                    append(" LIMIT $limit")
                    if (offset > 0) {
                        append(" OFFSET $offset")
                    }
                }
            } else {
                null
            }

            val page = runCatching {
                contentResolver.queryWithFallback(
                    uris = spec.contentUris,
                    projection = PROJECTION,
                    selection = selection,
                    selectionArgs = args,
                    sortOrder = legacySortOrder,
                    bundle = bundle
                ) { contentUri, result ->
                    val idIndex = result.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val mimeIndex = result.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                    val dateTakenIndex =
                        result.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                    val dateAddedIndex =
                        result.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val items = buildList {
                        while (result.moveToNext()) {
                            val id = result.getLong(idIndex)
                            val uri = ContentUris.withAppendedId(contentUri, id)
                            val dateTaken = if (result.isNull(dateTakenIndex)) {
                                null
                            } else {
                                result.getLong(dateTakenIndex)
                                    .takeIf { it > 0 }
                            }
                            val dateAdded = if (result.isNull(dateAddedIndex)) {
                                null
                            } else {
                                result.getLong(dateAddedIndex)
                                    .takeIf { it > 0 }
                            }
                            val takenAtMillis = dateTaken ?: dateAdded?.let { it * 1000 }
                            val takenAt = takenAtMillis?.let(Instant::ofEpochMilli)
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
            }.getOrElse { error ->
                return LoadResult.Error(error)
            }

            return page ?: LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
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
                MediaStore.Images.Media.DATE_ADDED,
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
        private const val SORT_KEY_EXPRESSION =
            "CASE WHEN ${MediaStore.Images.Media.DATE_TAKEN} > 0 " +
                "THEN ${MediaStore.Images.Media.DATE_TAKEN} " +
                "ELSE ${MediaStore.Images.Media.DATE_ADDED} * 1000 END"
    }
}

private inline fun <T> ContentResolver.queryWithFallback(
    uris: List<Uri>,
    projection: Array<String>,
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String?,
    bundle: Bundle?,
    crossinline block: (Uri, Cursor) -> T
): T? {
    var lastIllegalArgument: IllegalArgumentException? = null
    for (uri in uris) {
        val cursor = try {
            if (bundle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                query(uri, projection, bundle, null)
            } else {
                query(uri, projection, selection, selectionArgs, sortOrder)
            }
        } catch (error: IllegalArgumentException) {
            lastIllegalArgument = error
            continue
        }
        if (cursor == null) {
            continue
        }
        cursor.use { result ->
            return block(uri, result)
        }
    }
    if (lastIllegalArgument != null) {
        throw lastIllegalArgument
    }
    return null
}
