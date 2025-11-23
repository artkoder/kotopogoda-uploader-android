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
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams
import androidx.paging.PagingSource.LoadResult
import androidx.paging.PagingState
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.upload.UploadLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
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
import timber.log.Timber

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PhotoRepository @Inject constructor(
    private val folderRepository: FolderRepository,
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val resolver: ContentResolver = context.contentResolver
    private val hasLoggedInitialOrder = AtomicBoolean(false)

    fun observePhotos(anchor: Instant? = null): Flow<PagingData<PhotoItem>> =
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
                            WindowedPhotoPagingSource(
                                spec = spec,
                                anchorMillis = anchor?.toEpochMilli()
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

    suspend fun getAvailableDates(): Set<LocalDate> = withContext(ioDispatcher) {
        val folder = folderRepository.getFolder() ?: return@withContext emptySet()
        val spec = buildQuerySpec(folder)
        collectAvailableDates(spec, null, null)
    }

    suspend fun getAvailableDatesInRange(
        startInclusive: LocalDate,
        endExclusive: LocalDate
    ): Set<LocalDate> = withContext(ioDispatcher) {
        val folder = folderRepository.getFolder() ?: return@withContext emptySet()
        val spec = buildQuerySpec(folder)
        val zoneId = ZoneId.systemDefault()
        val startMillis = startInclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMillis = endExclusive.atStartOfDay(zoneId).toInstant().toEpochMilli()
        collectAvailableDates(spec, startMillis, endMillis)
    }

    suspend fun findPhotoOnOrBefore(instant: Instant): PhotoItem? = withContext(ioDispatcher) {
        val folder = folderRepository.getFolder() ?: return@withContext null
        val spec = buildQuerySpec(folder)
        val selection = "($SORT_KEY_EXPRESSION <= CAST(? AS INTEGER))"
        val args = arrayOf(instant.toEpochMilli().toString())
        querySinglePhoto(
            spec = spec,
            ascending = false,
            extraSelection = selection,
            extraSelectionArgs = args
        )
    }

    suspend fun findPhotoOnOrAfter(instant: Instant): PhotoItem? = withContext(ioDispatcher) {
        val folder = folderRepository.getFolder() ?: return@withContext null
        val spec = buildQuerySpec(folder)
        val selection = "($SORT_KEY_EXPRESSION >= CAST(? AS INTEGER))"
        val args = arrayOf(instant.toEpochMilli().toString())
        querySinglePhoto(
            spec = spec,
            ascending = true,
            extraSelection = selection,
            extraSelectionArgs = args
        )
    }

    private fun collectAvailableDates(
        spec: MediaStoreQuerySpec,
        rangeStartMillis: Long?,
        rangeEndMillis: Long?
    ): Set<LocalDate> {
        val projection = arrayOf(
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED
        )
        val dates = HashSet<LocalDate>()
        val zoneId = ZoneId.systemDefault()
        val selectionParts = mutableListOf<String>().apply {
            addAll(spec.selectionParts)
            if (rangeStartMillis != null && rangeEndMillis != null) {
                add("($SORT_KEY_EXPRESSION >= CAST(? AS INTEGER) AND $SORT_KEY_EXPRESSION < CAST(? AS INTEGER))")
            }
        }
        val selectionArgs = mutableListOf<String>().apply {
            addAll(spec.selectionArgs)
            if (rangeStartMillis != null && rangeEndMillis != null) {
                add(rangeStartMillis.toString())
                add(rangeEndMillis.toString())
            }
        }
        val selection = selectionParts.joinToString(separator = " AND ")
            .takeIf { it.isNotEmpty() }
        val selectionArgsArray = selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray()
        val bundle = bundleOf().apply {
            selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            selectionArgsArray?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
        }

        runCatching {
            resolver.queryWithFallback(
                uris = spec.contentUris,
                projection = projection,
                selection = selection,
                selectionArgs = selectionArgsArray,
                sortOrder = null,
                bundle = bundle
            ) { _, cursor ->
                val dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val dateModifiedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val dateTaken = if (dateTakenIndex >= 0 && !cursor.isNull(dateTakenIndex)) {
                        cursor.getLong(dateTakenIndex).takeIf { it > 0 }
                    } else {
                        null
                    }
                    val dateAdded = if (dateAddedIndex >= 0 && !cursor.isNull(dateAddedIndex)) {
                        cursor.getLong(dateAddedIndex).takeIf { it > 0 }
                    } else {
                        null
                    }
                    val dateModified = if (dateModifiedIndex >= 0 && !cursor.isNull(dateModifiedIndex)) {
                        cursor.getLong(dateModifiedIndex).takeIf { it > 0 }
                    } else {
                        null
                    }
                    val millis = dateTaken ?: dateAdded?.let { it * 1000 } ?: dateModified?.let { it * 1000 }
                    if (millis != null) {
                        val date = Instant.ofEpochMilli(millis).atZone(zoneId).toLocalDate()
                        dates.add(date)
                    }
                }
            }
        }.onFailure { error ->
            Timber.tag(MEDIA_LOG_TAG).e(
                error,
                UploadLog.message(
                    category = CATEGORY_MEDIA_ERROR,
                    action = "get_available_dates",
                    details = arrayOf(
                        "uri_count" to spec.contentUris.size,
                        "has_range" to (rangeStartMillis != null),
                    ),
                ),
            )
        }
        return dates
    }

    suspend fun findIndexAtOrAfter(date: Instant): Int = withContext(ioDispatcher) {
        val folder = folderRepository.getFolder() ?: return@withContext 0
        val spec = buildQuerySpec(folder)
        maybeLogInitialPhotoOrder(spec)
        val targetMillis = date.toEpochMilli()
        val (selection, args) = buildTimestampLowerBoundSelection(targetMillis, inclusive = false)
        val newerCount = queryCount(spec, selection, args)
        val total = queryCount(spec)
        if (total == 0) {
            0
        } else {
            newerCount.coerceIn(0, max(0, total - 1))
        }
    }

    suspend fun findIndexAtOrAfter(
        start: Instant,
        endExclusive: Instant,
        requestId: String? = null,
        selectedDate: String? = null
    ): Int? = withContext(ioDispatcher) {
        val folder = folderRepository.getFolder() ?: return@withContext null
        if (!endExclusive.isAfter(start)) {
            return@withContext null
        }
        val spec = buildQuerySpec(folder)
        maybeLogInitialPhotoOrder(spec)
        val rangeStartMillis = start.toEpochMilli()
        val rangeEndMillis = endExclusive.toEpochMilli()
        
        if (requestId != null && selectedDate != null) {
            Timber.tag(CALENDAR_DEBUG_TAG).i(
                "CalendarQueryStart date=%s rangeStartMillis=%d rangeEndMillis=%d source=MediaStore sort=DATE_TAKEN_DESC",
                selectedDate,
                rangeStartMillis,
                rangeEndMillis
            )
        }
        
        val (beforeSelection, beforeArgs) =
            buildTimestampLowerBoundSelection(rangeEndMillis, inclusive = true)
        val beforeCount = queryCount(spec, beforeSelection, beforeArgs)
        val (rangeSelection, rangeArgs) = buildTimestampRangeSelection(rangeStartMillis, rangeEndMillis)
        val inRangeCount = queryCount(spec, rangeSelection, rangeArgs)
        
        if (requestId != null && selectedDate != null) {
            if (inRangeCount == 0) {
                Timber.tag(CALENDAR_DEBUG_TAG).i(
                    "CalendarQueryResult date=%s queryCount=0",
                    selectedDate
                )
            } else {
                Timber.tag(CALENDAR_DEBUG_TAG).i(
                    "CalendarQueryResult date=%s queryCount=%d targetIndex=%d",
                    selectedDate,
                    inRangeCount,
                    beforeCount
                )
                logPhotosInRange(spec, rangeStartMillis, rangeEndMillis, requestId, selectedDate)
            }
        }
        
        if (inRangeCount == 0) {
            null
        } else {
            beforeCount
        }
    }

    suspend fun getPhotoAt(index: Int): PhotoItem? = withContext(ioDispatcher) {
        val folder = folderRepository.getFolder() ?: return@withContext null
        val spec = buildQuerySpec(folder)
        maybeLogInitialPhotoOrder(spec)
        queryPhotoAt(spec, index.coerceAtLeast(0))
    }

    suspend fun clampIndex(index: Int): Int = withContext(ioDispatcher) {
        val total = countAll()
        if (total == 0) {
            0
        } else {
            index.coerceIn(0, total - 1)
        }
    }

    private fun queryPhotoAt(spec: MediaStoreQuerySpec, index: Int): PhotoItem? =
        querySinglePhoto(spec, ascending = false, offset = index)

    private fun querySinglePhoto(
        spec: MediaStoreQuerySpec,
        ascending: Boolean,
        offset: Int = 0,
        extraSelection: String? = null,
        extraSelectionArgs: Array<String>? = null,
    ): PhotoItem? {
        val selectionParts = mutableListOf<String>().apply {
            addAll(spec.selectionParts)
            extraSelection?.let { add(it) }
        }
        val selectionArgsList = mutableListOf<String>().apply {
            addAll(spec.selectionArgs)
            extraSelectionArgs?.let { addAll(it) }
        }
        val selection = selectionParts.joinToString(separator = " AND ").takeIf { it.isNotEmpty() }
        val selectionArgs = selectionArgsList.takeIf { it.isNotEmpty() }?.toTypedArray()
        val (sortOrder, bundle) = buildQueryArgs(
            selection = selection,
            selectionArgs = selectionArgs,
            limit = 1,
            offset = offset.takeIf { it > 0 },
            ascending = ascending,
        )

        return runCatching {
            resolver.queryWithFallback(
                uris = spec.contentUris,
                projection = PHOTO_PROJECTION,
                selection = selection,
                selectionArgs = selectionArgs,
                sortOrder = sortOrder,
                bundle = bundle
            ) { contentUri, cursor ->
                cursor.readPhotoEntries(contentUri).firstOrNull()?.item
            }
        }.onFailure { error ->
            Timber.tag(MEDIA_LOG_TAG).e(
                error,
                UploadLog.message(
                    category = CATEGORY_MEDIA_ERROR,
                    action = "single_photo",
                    details = arrayOf(
                        "ascending" to ascending,
                        "offset" to offset,
                        "has_extra" to (extraSelection != null),
                    ),
                ),
            )
        }.getOrNull()
    }

    private fun maybeLogInitialPhotoOrder(spec: MediaStoreQuerySpec) {
        if (!hasLoggedInitialOrder.compareAndSet(false, true)) {
            return
        }
        val newest = querySinglePhoto(spec, ascending = false)
        val oldest = querySinglePhoto(spec, ascending = true)
        Timber.tag(CALENDAR_DEBUG_TAG).i(
            "Initial photo order check: firstTakenAt=%s firstUri=%s lastTakenAt=%s lastUri=%s",
            newest?.takenAt?.toString() ?: "null",
            newest?.uri?.toString() ?: "null",
            oldest?.takenAt?.toString() ?: "null",
            oldest?.uri?.toString() ?: "null"
        )
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
        Timber.tag(MEDIA_LOG_TAG).v(
            UploadLog.message(
                category = CATEGORY_MEDIA_COUNT_REQUEST,
                action = "count",
                details = arrayOf(
                    "has_extra" to (extraSelection != null),
                    "uri_count" to spec.contentUris.size,
                ),
            ),
        )
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
        }
            .onSuccess { count ->
                Timber.tag(MEDIA_LOG_TAG).i(
                    UploadLog.message(
                        category = CATEGORY_MEDIA_COUNT_RESULT,
                        action = "count",
                        details = arrayOf(
                            "value" to count,
                            "has_extra" to (extraSelection != null),
                        ),
                    ),
                )
            }
            .onFailure { error ->
                Timber.tag(MEDIA_LOG_TAG).e(
                    error,
                    UploadLog.message(
                        category = CATEGORY_MEDIA_ERROR,
                        action = "count",
                        details = arrayOf(
                            "has_extra" to (extraSelection != null),
                        ),
                    ),
                )
            }
            .getOrNull() ?: 0
    }

    private fun Cursor.getCountSafely(): Int =
        runCatching { count }.getOrDefault(0)

    private fun buildTimestampLowerBoundSelection(
        thresholdMillis: Long,
        inclusive: Boolean
    ): Pair<String, Array<String>> {
        val operator = if (inclusive) ">=" else ">"
        val selection = "($SORT_KEY_EXPRESSION $operator CAST(? AS INTEGER))"
        val args = arrayOf(thresholdMillis.toString())
        return selection to args
    }

    private fun buildTimestampRangeSelection(
        startMillisInclusive: Long,
        endMillisExclusive: Long
    ): Pair<String, Array<String>> {
        val selection = "($SORT_KEY_EXPRESSION >= CAST(? AS INTEGER) AND $SORT_KEY_EXPRESSION < CAST(? AS INTEGER))"
        val start = startMillisInclusive.toString()
        val end = endMillisExclusive.toString()
        val args = arrayOf(start, end)
        return selection to args
    }

    private fun buildQueryArgs(
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int?,
        offset: Int?,
        ascending: Boolean = false,
    ): Pair<String?, Bundle> {
        val legacySortOrder = buildSortOrder(limit = limit, offset = offset, ascending = ascending)
        val sortExpression = buildSortOrder(limit = null, offset = null, ascending = ascending)

        val bundle = bundleOf().apply {
            selection?.let {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it)
            }
            selectionArgs?.let {
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it)
            }
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortExpression)
            limit?.let { putInt(ContentResolver.QUERY_ARG_LIMIT, it) }
            offset?.takeIf { it > 0 }?.let { putInt(ContentResolver.QUERY_ARG_OFFSET, it) }
        }

        return legacySortOrder to bundle
    }

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

    private data class PhotoWindowKey(
        val sortKey: Long,
        val mediaId: Long,
    )

    private data class PhotoEntry(
        val mediaId: Long,
        val sortKey: Long,
        val item: PhotoItem,
    )

    private inner class WindowedPhotoPagingSource(
        private val spec: MediaStoreQuerySpec,
        private val anchorMillis: Long?,
    ) : PagingSource<PhotoWindowKey, PhotoItem>() {

        override suspend fun load(params: LoadParams<PhotoWindowKey>): LoadResult<PhotoWindowKey, PhotoItem> {
            return runCatching {
                loadInternal(params)
            }.getOrElse { error ->
                Timber.tag(MEDIA_LOG_TAG).e(
                    error,
                    UploadLog.message(
                        category = CATEGORY_MEDIA_ERROR,
                        action = "window_page",
                        details = arrayOf(
                            "load_type" to params.loadType.name,
                            "has_anchor" to (anchorMillis != null),
                        ),
                    ),
                )
                LoadResult.Error(error)
            }
        }

        private suspend fun loadInternal(
            params: LoadParams<PhotoWindowKey>
        ): LoadResult<PhotoWindowKey, PhotoItem> {
            val limit = params.loadSize.coerceAtLeast(1)
            val selectionParts = mutableListOf<String>().apply {
                addAll(spec.selectionParts)
            }
            val selectionArgs = mutableListOf<String>().apply {
                addAll(spec.selectionArgs)
            }

            when (params.loadType) {
                LoadType.REFRESH -> {
                    val upperBound = params.key?.sortKey ?: anchorMillis
                    if (upperBound != null) {
                        selectionParts += "($SORT_KEY_EXPRESSION <= CAST(? AS INTEGER))"
                        selectionArgs += upperBound.toString()
                    }
                }
                LoadType.APPEND -> {
                    val key = params.key ?: return emptyPage()
                    val (selection, args) = buildOlderThanSelection(key)
                    selectionParts += selection
                    selectionArgs += args
                }
                LoadType.PREPEND -> {
                    val key = params.key ?: return emptyPage()
                    val (selection, args) = buildNewerThanSelection(key)
                    selectionParts += selection
                    selectionArgs += args
                }
            }

            val selection = selectionParts.joinToString(separator = " AND ").takeIf { it.isNotEmpty() }
            val argsArray = selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray()
            val (legacySortOrder, bundle) = buildQueryArgs(
                selection = selection,
                selectionArgs = argsArray,
                limit = limit,
                offset = null,
            )

            Timber.tag(MEDIA_LOG_TAG).i(
                UploadLog.message(
                    category = CATEGORY_MEDIA_REQUEST,
                    action = "window_page",
                    details = arrayOf(
                        "load_type" to params.loadType.name,
                        "limit" to limit,
                        "has_anchor" to (anchorMillis != null),
                        "uri_count" to spec.contentUris.size,
                    ),
                ),
            )

            val entries = resolver.queryWithFallback(
                uris = spec.contentUris,
                projection = PHOTO_PROJECTION,
                selection = selection,
                selectionArgs = argsArray,
                sortOrder = legacySortOrder,
                bundle = bundle
            ) { contentUri, cursor ->
                cursor.readPhotoEntries(contentUri)
            } ?: emptyList()

            if (entries.isEmpty()) {
                return emptyPage()
            }

            val items = entries.map(PhotoEntry::item)
            val firstKey = entries.first().toKey()
            val lastKey = entries.last().toKey()

            Timber.tag(MEDIA_LOG_TAG).i(
                UploadLog.message(
                    category = CATEGORY_MEDIA_RESULT,
                    action = "window_page",
                    details = arrayOf(
                        "returned" to items.size,
                        "load_type" to params.loadType.name,
                        "first_sort" to firstKey.sortKey,
                        "last_sort" to lastKey.sortKey,
                    ),
                ),
            )

            return LoadResult.Page(
                data = items,
                prevKey = firstKey,
                nextKey = lastKey,
                itemsBefore = LoadResult.Page.COUNT_UNDEFINED,
                itemsAfter = LoadResult.Page.COUNT_UNDEFINED
            )
        }

        override fun getRefreshKey(state: PagingState<PhotoWindowKey, PhotoItem>): PhotoWindowKey? {
            val anchorPosition = state.anchorPosition ?: return null
            val anchorItem = state.closestItemToPosition(anchorPosition) ?: return null
            val sortKey = anchorItem.sortKeyMillis ?: anchorItem.takenAt?.toEpochMilli() ?: return null
            val mediaId = anchorItem.id.toLongOrNull() ?: return null
            return PhotoWindowKey(sortKey = sortKey, mediaId = mediaId)
        }

        private fun emptyPage(): LoadResult.Page<PhotoWindowKey, PhotoItem> = LoadResult.Page(
            data = emptyList(),
            prevKey = null,
            nextKey = null,
            itemsBefore = LoadResult.Page.COUNT_UNDEFINED,
            itemsAfter = LoadResult.Page.COUNT_UNDEFINED
        )

        private fun buildOlderThanSelection(key: PhotoWindowKey): Pair<String, List<String>> {
            val selection = buildString {
                append("(")
                append(SORT_KEY_EXPRESSION)
                append(" < CAST(? AS INTEGER) OR (")
                append(SORT_KEY_EXPRESSION)
                append(" = CAST(? AS INTEGER) AND ")
                append(MediaStore.Images.Media._ID)
                append(" < CAST(? AS INTEGER)))")
            }
            val args = listOf(
                key.sortKey.toString(),
                key.sortKey.toString(),
                key.mediaId.toString()
            )
            return selection to args
        }

        private fun buildNewerThanSelection(key: PhotoWindowKey): Pair<String, List<String>> {
            val selection = buildString {
                append("(")
                append(SORT_KEY_EXPRESSION)
                append(" > CAST(? AS INTEGER) OR (")
                append(SORT_KEY_EXPRESSION)
                append(" = CAST(? AS INTEGER) AND ")
                append(MediaStore.Images.Media._ID)
                append(" > CAST(? AS INTEGER)))")
            }
            val args = listOf(
                key.sortKey.toString(),
                key.sortKey.toString(),
                key.mediaId.toString()
            )
            return selection to args
        }

        private fun PhotoEntry.toKey(): PhotoWindowKey = PhotoWindowKey(
            sortKey = sortKey,
            mediaId = mediaId,
        )
    }

    private fun logPhotosInRange(
        spec: MediaStoreQuerySpec,
        rangeStartMillis: Long,
        rangeEndMillis: Long,
        requestId: String,
        selectedDate: String
    ) {
        val (rangeSelection, rangeArgs) = buildTimestampRangeSelection(rangeStartMillis, rangeEndMillis)
        val selectionParts = mutableListOf<String>().apply {
            addAll(spec.selectionParts)
            add(rangeSelection)
        }
        val args = mutableListOf<String>().apply {
            addAll(spec.selectionArgs)
            addAll(rangeArgs)
        }
        val selectionString = selectionParts.joinToString(separator = " AND ").takeIf { it.isNotEmpty() }
        val selectionArgsArray = args.takeIf { it.isNotEmpty() }?.toTypedArray()
        val (sortOrder, bundle) = buildQueryArgs(
            selection = selectionString,
            selectionArgs = selectionArgsArray,
            limit = 5,
            offset = null,
        )
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATA
        )
        
        runCatching {
            resolver.queryWithFallback(
                uris = spec.contentUris,
                projection = projection,
                selection = selectionString,
                selectionArgs = selectionArgsArray,
                sortOrder = sortOrder,
                bundle = bundle
            ) { contentUri, cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val dateModifiedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val dataIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                
                var itemIndex = 0
                val zoneId = ZoneId.systemDefault()
                while (cursor.moveToNext() && itemIndex < 5) {
                    val id = if (idIndex >= 0) cursor.getLong(idIndex) else -1
                    val dateTaken = if (dateTakenIndex >= 0 && !cursor.isNull(dateTakenIndex)) {
                        cursor.getLong(dateTakenIndex).takeIf { it > 0 }
                    } else {
                        null
                    }
                    val dateAdded = if (dateAddedIndex >= 0 && !cursor.isNull(dateAddedIndex)) {
                        cursor.getLong(dateAddedIndex).takeIf { it > 0 }
                    } else {
                        null
                    }
                    val dateModified = if (dateModifiedIndex >= 0 && !cursor.isNull(dateModifiedIndex)) {
                        cursor.getLong(dateModifiedIndex).takeIf { it > 0 }
                    } else {
                        null
                    }
                    val path = if (dataIndex >= 0 && !cursor.isNull(dataIndex)) {
                        cursor.getString(dataIndex)
                    } else {
                        null
                    }
                    
                    val takenMillis = dateTaken
                        ?: dateAdded?.let { it * 1000 }
                        ?: dateModified?.let { it * 1000 }
                    val takenStr = if (takenMillis != null) {
                        Instant.ofEpochMilli(takenMillis).atZone(zoneId).toString()
                    } else {
                        "null"
                    }
                    
                    val uri = ContentUris.withAppendedId(contentUri, id)
                    Timber.tag(CALENDAR_DEBUG_TAG).i(
                        "CalendarQueryPhoto date=%s index=%d takenAt=%s uri=%s path=%s",
                        selectedDate,
                        itemIndex,
                        takenStr,
                        uri.toString(),
                        path ?: "null"
                    )
                    itemIndex++
                }
            }
        }.onFailure { error ->
            Timber.tag(CALENDAR_DEBUG_TAG).e(
                error,
                "CalendarQueryPhoto date=%s error fetching photo details",
                selectedDate
            )
        }
    }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 60
        private const val DEFAULT_PREFETCH_DISTANCE = 30
        internal const val SORT_KEY_EXPRESSION =
            "CASE WHEN ${MediaStore.Images.Media.DATE_TAKEN} > 0 " +
                "THEN ${MediaStore.Images.Media.DATE_TAKEN} " +
                "WHEN ${MediaStore.Images.Media.DATE_ADDED} > 0 " +
                "THEN ${MediaStore.Images.Media.DATE_ADDED} * 1000 " +
                "ELSE ${MediaStore.Images.Media.DATE_MODIFIED} * 1000 END"
        private const val MEDIA_LOG_TAG = "MediaStore"
        private const val CALENDAR_DEBUG_TAG = "ViewerCalendar"
        private const val CATEGORY_MEDIA_REQUEST = "MEDIA_QUERY/REQUEST"
        private const val CATEGORY_MEDIA_RESULT = "MEDIA_QUERY/RESULT"
        private const val CATEGORY_MEDIA_COUNT_REQUEST = "MEDIA_QUERY/COUNT_REQUEST"
        private const val CATEGORY_MEDIA_COUNT_RESULT = "MEDIA_QUERY/COUNT_RESULT"
        private const val CATEGORY_MEDIA_ERROR = "MEDIA_QUERY/ERROR"

        private val PHOTO_PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE
        )

        private fun buildSortOrder(
            limit: Int?,
            offset: Int?,
            ascending: Boolean = false
        ): String {
            val direction = if (ascending) "ASC" else "DESC"
            return buildString {
                append("$SORT_KEY_EXPRESSION $direction")
                limit?.let {
                    append(" LIMIT $it")
                }
                offset?.takeIf { it > 0 }?.let {
                    append(" OFFSET $it")
                }
            }
        }
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

private fun Cursor.readPhotoEntries(contentUri: Uri): List<PhotoEntry> {
    val idIndex = getColumnIndexOrThrow(MediaStore.Images.Media._ID)
    val mimeIndex = getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
    val dateTakenIndex = getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
    val dateAddedIndex = getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
    val dateModifiedIndex = getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
    val entries = mutableListOf<PhotoEntry>()
    while (moveToNext()) {
        val mime = getStringOrNull(mimeIndex)
        if (mime?.startsWith("image/") != true) {
            continue
        }
        val id = getLong(idIndex)
        val uri = ContentUris.withAppendedId(contentUri, id)
        val sortKeyMillis = readMillis(dateTakenIndex, seconds = false)
            ?: readMillis(dateAddedIndex, seconds = true)
            ?: readMillis(dateModifiedIndex, seconds = true)
        val normalizedSortKey = sortKeyMillis ?: 0L
        val takenAt = sortKeyMillis?.let(Instant::ofEpochMilli)
        val item = PhotoItem(
            id = id.toString(),
            uri = uri,
            takenAt = takenAt,
            sortKeyMillis = normalizedSortKey,
        )
        entries += PhotoEntry(
            mediaId = id,
            sortKey = normalizedSortKey,
            item = item
        )
    }
    return entries
}

private fun Cursor.getStringOrNull(index: Int): String? {
    if (index < 0 || isNull(index)) {
        return null
    }
    return getString(index)
}

private fun Cursor.readMillis(index: Int, seconds: Boolean): Long? {
    if (index < 0 || isNull(index)) {
        return null
    }
    val value = getLong(index)
    if (value <= 0) {
        return null
    }
    return if (seconds) value * 1000 else value
}
