package com.kotopogoda.uploader.feature.viewer

import android.app.PendingIntent
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.kotopogoda.uploader.feature.viewer.R
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.data.sa.MoveResult
import com.kotopogoda.uploader.core.data.sa.SaFileRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import com.kotopogoda.uploader.core.settings.reviewProgressFolderId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayList
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.ArrayDeque
import kotlin.text.Charsets
import kotlin.math.max
import timber.log.Timber

@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val folderRepository: FolderRepository,
    private val saFileRepository: SaFileRepository,
    private val uploadEnqueuer: UploadEnqueuer,
    private val uploadQueueRepository: UploadQueueRepository,
    private val reviewProgressStore: ReviewProgressStore,
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    val photos: Flow<PagingData<PhotoItem>> = photoRepository.observePhotos()
        .cachedIn(viewModelScope)

    private val _isPagerScrollEnabled = MutableStateFlow(true)
    val isPagerScrollEnabled: StateFlow<Boolean> = _isPagerScrollEnabled.asStateFlow()

    private val startIndexArgument: Int =
        savedStateHandle.get<Int>(VIEWER_START_INDEX_ARG)?.coerceAtLeast(0) ?: 0

    private val currentIndexKey = "viewer_current_index"
    private val _currentIndex: MutableStateFlow<Int> =
        MutableStateFlow(savedStateHandle.get<Int>(currentIndexKey) ?: startIndexArgument)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val folderId = MutableStateFlow<String?>(null)
    private val anchorDate = MutableStateFlow<Instant?>(null)
    private val photoCount = MutableStateFlow(0)
    private val currentPhoto = MutableStateFlow<PhotoItem?>(null)
    private val _currentFolderTreeUri = MutableStateFlow<String?>(null)
    val currentFolderTreeUri: StateFlow<String?> = _currentFolderTreeUri.asStateFlow()

    private val undoStack = ArrayDeque<UserAction>()
    private val undoStackKey = "viewer_undo_stack"
    private val handledDeletionWarnings = mutableSetOf<Long>()

    private val _undoCount = MutableStateFlow(0)
    val undoCount: StateFlow<Int> = _undoCount.asStateFlow()

    private val initialIndexRestored = MutableStateFlow(false)
    private var pendingInitialIndex: Int? = null

    val canUndo: StateFlow<Boolean> = undoCount
        .map { it > 0 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _undoCount.value > 0
        )

    private val _actionInProgress = MutableStateFlow<ViewerActionInProgress?>(null)
    val actionInProgress: StateFlow<ViewerActionInProgress?> = _actionInProgress.asStateFlow()

    private val _events = MutableSharedFlow<ViewerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ViewerEvent> = _events.asSharedFlow()

    private val _selection = MutableStateFlow<Set<PhotoItem>>(emptySet())
    val selection: StateFlow<Set<PhotoItem>> = _selection.asStateFlow()

    val isSelectionMode: StateFlow<Boolean> = selection
        .map { it.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _selection.value.isNotEmpty()
        )

    private var pendingDelete: PendingDelete? = null
    private var pendingBatchDelete: PendingBatchDelete? = null
    private var pendingBatchMove: PendingBatchMove? = null
    private var pendingSingleMove: PendingSingleMove? = null

    private fun logUi(category: String, action: String, uri: Uri? = null, vararg details: Pair<String, Any?>) {
        Timber.tag(UI_TAG).i(
            UploadLog.message(
                category = category,
                action = action,
                uri = uri,
                details = details,
            )
        )
    }

    private fun logUiError(category: String, action: String, error: Throwable, uri: Uri? = null, vararg details: Pair<String, Any?>) {
        Timber.tag(UI_TAG).e(
            error,
            UploadLog.message(
                category = category,
                action = action,
                uri = uri,
                details = details,
            )
        )
    }

    private fun logPermissionRequest(type: MovePermissionType, uri: Uri?) {
        Timber.tag(PERMISSION_TAG).i(
            UploadLog.message(
                category = "PERM/REQUEST",
                action = "storage_${type.name.lowercase()}",
                uri = uri,
                details = arrayOf(
                    "source" to "viewer",
                ),
            )
        )
    }

    private fun logPermissionResult(type: MovePermissionType, granted: Boolean, uri: Uri?) {
        Timber.tag(PERMISSION_TAG).i(
            UploadLog.message(
                category = "PERM/RESULT",
                action = "storage_${type.name.lowercase()}",
                uri = uri,
                details = arrayOf(
                    "source" to "viewer",
                    "granted" to granted,
                ),
            )
        )
    }

    init {
        restoreUndoStack()
        savedStateHandle[currentIndexKey] = _currentIndex.value

        viewModelScope.launch {
            folderRepository.observeFolder().collect { folder ->
                _currentFolderTreeUri.value = folder?.treeUri
                if (folder != null) {
                    val id = reviewProgressFolderId(folder.treeUri)
                    folderId.value = id
                    val stored = reviewProgressStore.loadPosition(id)
                    anchorDate.value = stored?.anchorDate
                    pendingInitialIndex = stored?.index?.coerceAtLeast(0)
                    initialIndexRestored.value = pendingInitialIndex == null
                    pendingInitialIndex?.let { index ->
                        updateCurrentIndexInternal(index)
                    }
                } else {
                    folderId.value = null
                    anchorDate.value = null
                    pendingInitialIndex = null
                    initialIndexRestored.value = true
                }
            }
        }

        viewModelScope.launch {
            combine(currentIndex, photoCount, initialIndexRestored) { index, count, restored ->
                Triple(index, count, restored)
            }.collect { (index, count, restored) ->
                val pending = pendingInitialIndex
                if (!restored) {
                    if (pending != null) {
                        if (count > 0) {
                            val clamped = clampToCount(pending, count)
                            updateCurrentIndexInternal(clamped)
                            pendingInitialIndex = null
                            initialIndexRestored.value = true
                        } else if (count == 0 && pending == 0) {
                            updateCurrentIndexInternal(0)
                            pendingInitialIndex = null
                            initialIndexRestored.value = true
                        }
                    } else {
                        initialIndexRestored.value = true
                    }
                    return@collect
                }

                val clamped = clampToCount(index, count)
                if (clamped != index) {
                    setCurrentIndex(clamped)
                }
            }
        }

        viewModelScope.launch {
            combine(currentIndex, currentPhoto, initialIndexRestored) { index, photo, restored ->
                Triple(index, photo?.takenAt, restored)
            }
                .debounce(300)
                .collect { (index, takenAt, restored) ->
                    if (restored) {
                        persistProgress(index, takenAt)
                    }
                }
        }

        viewModelScope.launch {
            uploadQueueRepository.observeQueue().collect { items ->
                items.forEach { item ->
                    if (
                        item.state == UploadItemState.FAILED &&
                        item.lastErrorKind == UploadErrorKind.REMOTE_FAILURE &&
                        handledDeletionWarnings.add(item.entity.id)
                    ) {
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_delete_failed
                            )
                        )
                    }
                }
            }
        }
    }

    fun setPagerScrollEnabled(isEnabled: Boolean) {
        _isPagerScrollEnabled.value = isEnabled
    }

    fun setCurrentIndex(index: Int) {
        val normalized = index.coerceAtLeast(0)
        if (_currentIndex.value == normalized) {
            return
        }
        updateCurrentIndexInternal(normalized)
    }

    private fun updateCurrentIndexInternal(index: Int) {
        val normalized = index.coerceAtLeast(0)
        _currentIndex.value = normalized
        savedStateHandle[currentIndexKey] = normalized
    }

    fun jumpToDate(target: Instant) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val localDate = target.atZone(zone).toLocalDate()
            val startOfDay = localDate.atStartOfDay(zone).toInstant()
            val endOfDay = localDate.plusDays(1).atStartOfDay(zone).toInstant()
            Timber.tag(UI_TAG).i(
                UploadLog.message(
                    category = "CALENDAR/SELECT_DATE",
                    action = "jump_to_date",
                    details = arrayOf(
                        "start_ms" to startOfDay.toEpochMilli(),
                        "end_ms" to endOfDay.toEpochMilli(),
                        "timezone" to zone.id,
                    ),
                ),
            )
            Timber.tag(UI_TAG).i(
                UploadLog.message(
                    category = "MEDIA_QUERY/REQUEST",
                    action = "find_index_at_or_after",
                    details = arrayOf(
                        "start_ms" to startOfDay.toEpochMilli(),
                        "end_ms" to endOfDay.toEpochMilli(),
                        "sort" to "taken_desc",
                    ),
                ),
            )
            val startMillis = SystemClock.elapsedRealtime()
            val resolvedIndex = photoRepository.findIndexAtOrAfter(startOfDay, endOfDay)
            val durationMillis = SystemClock.elapsedRealtime() - startMillis
            val resultDetails = if (resolvedIndex == null) {
                arrayOf(
                    "duration_ms" to durationMillis,
                    "count_in_day" to 0,
                )
            } else {
                arrayOf(
                    "duration_ms" to durationMillis,
                    "index" to resolvedIndex,
                )
            }
            Timber.tag(UI_TAG).i(
                UploadLog.message(
                    category = "CALENDAR/RESULT",
                    action = "jump_to_date",
                    details = resultDetails,
                ),
            )
            if (resolvedIndex == null) {
                _events.emit(ViewerEvent.ShowToast(R.string.viewer_toast_no_photos_for_day))
            } else {
                setCurrentIndex(resolvedIndex)
            }
        }
    }

    fun scrollToNewest() {
        val targetIndex = clampToCount(0, photoCount.value)
        updateCurrentIndexInternal(targetIndex)
        clearSelection()
        if (undoStack.isNotEmpty()) {
            undoStack.clear()
            persistUndoStack()
        }
        viewModelScope.launch {
            persistProgress(targetIndex, Instant.now())
        }
    }

    fun onFolderSelected(treeUri: String, flags: Int) {
        Timber.tag("UI").i("Folder selected: %s (flags=%d)", treeUri, flags)
        viewModelScope.launch {
            folderRepository.setFolder(treeUri, flags)
        }
    }

    fun onSkip(photo: PhotoItem?) {
        if (actionInProgress.value != null) {
            return
        }
        if (photo == null) {
            return
        }
        val fromIndex = currentIndex.value
        val toIndex = computeNextIndex(fromIndex)
        if (toIndex == fromIndex) {
            return
        }
        Timber.tag("UI").i(
            "Skip requested for %s (from=%d, to=%d)",
            photo.uri,
            fromIndex,
            toIndex
        )
        pushAction(UserAction.Skip(fromIndex = fromIndex, toIndex = toIndex))
        setCurrentIndex(toIndex)
        viewModelScope.launch {
            _events.emit(
                ViewerEvent.ShowSnackbar(
                    messageRes = R.string.viewer_snackbar_skip_success,
                    withUndo = true
                )
            )
        }
    }

    fun onPhotoLongPress(photo: PhotoItem) {
        if (_selection.value.isEmpty()) {
            _selection.value = setOf(photo)
            _isPagerScrollEnabled.value = false
        }
    }

    fun onToggleSelection(photo: PhotoItem) {
        _selection.update { current ->
            val updated = current.toMutableSet()
            if (!updated.add(photo)) {
                updated.remove(photo)
            }
            updated
        }
        if (_selection.value.isEmpty()) {
            _isPagerScrollEnabled.value = true
        }
    }

    fun clearSelection() {
        if (_selection.value.isNotEmpty()) {
            _selection.value = emptySet()
            _isPagerScrollEnabled.value = true
        }
    }

    fun onCancelSelection() {
        clearSelection()
    }

    fun onMoveSelection() {
        requestMoveSelectionToProcessing(_selection.value.toList())
    }

    fun onDeleteSelection() {
        requestDeleteSelection(_selection.value.toList())
    }

    fun onMoveToProcessing(photo: PhotoItem?) {
        if (_actionInProgress.value != null) {
            return
        }
        val current = photo ?: return
        Timber.tag("UI").i("Move to processing requested for %s", current.uri)
        viewModelScope.launch {
            try {
                val documentInfo = loadDocumentInfo(current.uri)
                val fromIndex = currentIndex.value
                val toIndex = computeNextIndex(fromIndex)
                pendingSingleMove = PendingSingleMove(
                    photo = current,
                    documentInfo = documentInfo,
                    fromIndex = fromIndex,
                    toIndex = toIndex
                )
                requestMoveSelectionToProcessing(listOf(current))
            } catch (error: Exception) {
                pendingSingleMove = null
                Timber.tag("UI").e(
                    error,
                    "Failed to prepare move %s to processing",
                    current.uri
                )
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_processing_failed
                    )
                )
            }
        }
    }

    private fun requestMoveSelectionToProcessing(photos: List<PhotoItem>) {
        if (_actionInProgress.value != null) {
            return
        }
        if (photos.isEmpty()) {
            return
        }
        viewModelScope.launch {
            _actionInProgress.value = ViewerActionInProgress.Processing
            try {
                pendingBatchMove = PendingBatchMove(photos = photos)
                finalizePendingBatchMove()
            } catch (error: Exception) {
                Timber.tag("UI").e(error, "Failed to request batch move")
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_processing_failed
                    )
                )
                pendingBatchMove = null
                _actionInProgress.value = null
                pendingSingleMove = null
                clearSelection()
            }
        }
    }

    fun onEnqueueUpload(photo: PhotoItem?) {
        if (_actionInProgress.value != null) {
            return
        }
        val current = photo ?: return
        logUi(
            category = "UI/CLICK_PUBLISH",
            action = "enqueue_request",
            uri = current.uri,
            "current_index" to currentIndex.value,
        )
        viewModelScope.launch {
            _actionInProgress.value = ViewerActionInProgress.Upload
            try {
                val documentInfo = loadDocumentInfo(current.uri)
                val fromIndex = currentIndex.value
                val toIndex = computeNextIndex(fromIndex)
                val idempotencyKey = buildIdempotencyKey(documentInfo)
                uploadEnqueuer.enqueue(
                    uri = current.uri,
                    idempotencyKey = idempotencyKey,
                    displayName = documentInfo.displayName
                )
                pushAction(
                    UserAction.EnqueuedUpload(
                        uri = current.uri,
                        fromIndex = fromIndex,
                        toIndex = toIndex
                    )
                )
                if (toIndex != fromIndex) {
                    setCurrentIndex(toIndex)
                }
                persistProgress(toIndex, current.takenAt)
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_publish_success,
                        withUndo = true
                    )
                )
                logUi(
                    category = "UI/PUBLISH_OK",
                    action = "enqueue_success",
                    uri = current.uri,
                    "from_index" to fromIndex,
                    "to_index" to toIndex,
                    "idempotency_key" to idempotencyKey,
                )
            } catch (error: Exception) {
                persistUndoStack()
                logUiError(
                    category = "UI/PUBLISH_ERROR",
                    action = "enqueue_failure",
                    error = error,
                    uri = current.uri,
                )
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_publish_failed
                    )
                )
            } finally {
                _actionInProgress.value = null
            }
        }
    }

    fun onDelete(photo: PhotoItem?) {
        if (_actionInProgress.value != null) {
            return
        }
        val current = photo ?: return
        logUi(
            category = "UI/CLICK_DELETE_SINGLE",
            action = "delete_request",
            uri = current.uri,
            "current_index" to currentIndex.value,
        )
        viewModelScope.launch {
            _actionInProgress.value = ViewerActionInProgress.Delete
            try {
                val documentInfo = loadDocumentInfo(current.uri)
                val fromIndex = currentIndex.value
                val toIndex = computeNextIndex(fromIndex)
                val backup = runCatching { createDeleteBackup(documentInfo) }.getOrNull()
                if (isAtLeastR()) {
                    val pendingIntent = withContext(Dispatchers.IO) {
                        MediaStore.createDeleteRequest(
                            context.contentResolver,
                            listOf(documentInfo.uri)
                        )
                    }
                    pendingDelete = PendingDelete(
                        photo = current,
                        documentInfo = documentInfo,
                        backup = backup,
                        fromIndex = fromIndex,
                        toIndex = toIndex
                    )
                    logPermissionRequest(MovePermissionType.Delete, documentInfo.uri)
                    _events.emit(
                        ViewerEvent.RequestDelete(intentSender = pendingIntent.intentSender)
                    )
                } else {
                    val pending = PendingDelete(
                        photo = current,
                        documentInfo = documentInfo,
                        backup = backup,
                        fromIndex = fromIndex,
                        toIndex = toIndex
                    )
                    val deleted = withContext(Dispatchers.IO) {
                        context.contentResolver.delete(documentInfo.uri, null, null) > 0
                    }
                    if (deleted) {
                        try {
                            finalizeDeletion(pending)
                        } catch (error: Exception) {
                            pending.backup?.delete()
                            logUiError(
                                category = "UI/DELETE_ERROR",
                                action = "delete_finalize_failure",
                                error = error,
                                uri = documentInfo.uri,
                            )
                            _events.emit(
                                ViewerEvent.ShowSnackbar(
                                    messageRes = R.string.viewer_snackbar_delete_failed
                                )
                            )
                            _actionInProgress.value = null
                        }
                    } else {
                        backup?.delete()
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_delete_failed
                            )
                        )
                        _actionInProgress.value = null
                    }
                }
            } catch (error: Exception) {
                pendingDelete?.backup?.delete()
                pendingDelete = null
                logUiError(
                    category = "UI/DELETE_ERROR",
                    action = "delete_request_failure",
                    error = error,
                    uri = current.uri,
                )
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_delete_failed
                    )
                )
                _actionInProgress.value = null
            }
        }
    }

    private fun requestDeleteSelection(photos: List<PhotoItem>) {
        if (_actionInProgress.value != null) {
            return
        }
        if (photos.isEmpty()) {
            return
        }
        logUi(
            category = "UI/CLICK_DELETE_BULK",
            action = "batch_delete_request",
            "count" to photos.size,
        )
        viewModelScope.launch {
            _actionInProgress.value = ViewerActionInProgress.Delete
            try {
                if (isAtLeastR()) {
                    val pendingIntent = withContext(Dispatchers.IO) {
                        MediaStore.createDeleteRequest(
                            context.contentResolver,
                            photos.map { it.uri }
                        )
                    }
                    pendingBatchDelete = PendingBatchDelete(photos)
                    logPermissionRequest(MovePermissionType.Delete, photos.firstOrNull()?.uri)
                    _events.emit(ViewerEvent.RequestDelete(pendingIntent.intentSender))
                    return@launch
                }
                finalizeBatchDelete(photos)
            } catch (error: Exception) {
                pendingBatchDelete = null
                logUiError(
                    category = "UI/DELETE_ERROR",
                    action = "batch_delete_request_failure",
                    error = error,
                )
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_delete_failed
                    )
                )
                _actionInProgress.value = null
            }
        }
    }

    fun onDeleteResult(result: DeleteResult) {
        val movePending = pendingBatchMove
        val deleteGranted = result == DeleteResult.Success
        val moveUri = movePending?.photos?.firstOrNull()?.uri
        if (movePending != null && movePending.requestType == MovePermissionType.Delete) {
            logPermissionResult(MovePermissionType.Delete, deleteGranted, moveUri)
            when (result) {
                DeleteResult.Success -> {
                    viewModelScope.launch {
                        pendingBatchMove = movePending.copy(requestType = null)
                        finalizePendingBatchMove()
                    }
                }
                DeleteResult.Cancelled, DeleteResult.Failed -> {
                    pendingBatchMove = null
                    pendingSingleMove = null
                    viewModelScope.launch {
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_processing_failed
                            )
                        )
                        clearSelection()
                        _actionInProgress.value = null
                    }
                }
            }
            return
        }
        val batch = pendingBatchDelete
        if (batch != null) {
            val batchUri = batch.photos.firstOrNull()?.uri
            logPermissionResult(MovePermissionType.Delete, deleteGranted, batchUri)
            when (result) {
                DeleteResult.Success -> {
                    viewModelScope.launch { finalizeBatchDelete(batch.photos) }
                }
                DeleteResult.Cancelled -> {
                    pendingBatchDelete = null
                    viewModelScope.launch {
                        _events.emit(ViewerEvent.ShowToast(R.string.viewer_toast_delete_cancelled))
                        clearSelection()
                        _actionInProgress.value = null
                    }
                }
                DeleteResult.Failed -> {
                    pendingBatchDelete = null
                    viewModelScope.launch {
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_delete_failed
                            )
                        )
                        clearSelection()
                        _actionInProgress.value = null
                    }
                }
            }
            return
        }
        val pending = pendingDelete
        if (pending == null) {
            _actionInProgress.value = null
            return
        }
        pendingDelete = null
        logPermissionResult(MovePermissionType.Delete, deleteGranted, pending.documentInfo.uri)
        viewModelScope.launch {
            when (result) {
                DeleteResult.Success -> {
                    try {
                        finalizeDeletion(pending)
                        logUi(
                            category = "UI/DELETE_OK",
                            action = "delete_success",
                            uri = pending.documentInfo.uri,
                        )
                    } catch (error: Exception) {
                        pending.backup?.delete()
                        logUiError(
                            category = "UI/DELETE_ERROR",
                            action = "delete_failure",
                            error = error,
                            uri = pending.documentInfo.uri,
                        )
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_delete_failed
                            )
                        )
                        _actionInProgress.value = null
                    }
                }
                DeleteResult.Cancelled -> {
                    pending.backup?.delete()
                    logUi(
                        category = "UI/DELETE_CANCEL",
                        action = "delete_cancelled",
                        uri = pending.documentInfo.uri,
                    )
                    _events.emit(ViewerEvent.ShowToast(R.string.viewer_toast_delete_cancelled))
                    _actionInProgress.value = null
                }
                DeleteResult.Failed -> {
                    pending.backup?.delete()
                    logUi(
                        category = "UI/DELETE_ERROR",
                        action = "delete_failed",
                        uri = pending.documentInfo.uri,
                    )
                    _events.emit(
                        ViewerEvent.ShowSnackbar(
                            messageRes = R.string.viewer_snackbar_delete_failed
                        )
                    )
                    _actionInProgress.value = null
                }
            }
        }
    }

    fun onWriteRequestResult(granted: Boolean) {
        val pending = pendingBatchMove
        val targetUri = pending?.photos?.firstOrNull()?.uri
        if (pending == null || pending.requestType != MovePermissionType.Write) {
            if (!granted) {
                logPermissionResult(MovePermissionType.Write, granted, targetUri)
                _actionInProgress.value = null
            }
            return
        }
        if (!granted) {
            pendingBatchMove = null
            pendingSingleMove = null
            viewModelScope.launch {
                logPermissionResult(MovePermissionType.Write, granted, targetUri)
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_processing_failed
                    )
                )
                clearSelection()
                _actionInProgress.value = null
            }
            return
        }
        viewModelScope.launch {
            logPermissionResult(MovePermissionType.Write, granted = true, uri = targetUri)
            pendingBatchMove = pending.copy(requestType = null)
            finalizePendingBatchMove()
        }
    }

    fun onUndo() {
        if (_actionInProgress.value != null) {
            return
        }
        val action = undoStack.removeLastOrNull() ?: return
        Timber.tag("UI").i("Undo requested for %s", action.javaClass.simpleName)
        persistUndoStack()
        when (action) {
            is UserAction.Skip -> {
                val targetIndex = clampIndex(action.fromIndex)
                setCurrentIndex(targetIndex)
            }
            is UserAction.MovedToProcessing -> {
                viewModelScope.launch {
                    _actionInProgress.value = ViewerActionInProgress.Processing
                    try {
                        saFileRepository.moveBack(
                            srcInProcessing = action.toUri,
                            originalParent = action.originalParent,
                            displayName = action.displayName
                        )
                        val targetIndex = clampIndex(action.fromIndex)
                        setCurrentIndex(targetIndex)
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_processing_undone
                            )
                        )
                    } catch (error: Exception) {
                        pushAction(action)
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_undo_failed
                            )
                        )
                    } finally {
                        _actionInProgress.value = null
                    }
                }
            }
            is UserAction.EnqueuedUpload -> {
                viewModelScope.launch {
                    _actionInProgress.value = ViewerActionInProgress.Upload
                    try {
                        uploadEnqueuer.cancel(action.uri)
                        val targetIndex = clampIndex(action.fromIndex)
                        setCurrentIndex(targetIndex)
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_publish_undone
                            )
                        )
                    } catch (error: Exception) {
                        pushAction(action)
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_undo_failed
                            )
                        )
                    } finally {
                        _actionInProgress.value = null
                    }
                }
            }
            is UserAction.Deleted -> {
                viewModelScope.launch {
                    _actionInProgress.value = ViewerActionInProgress.Delete
                    val restored = runCatching { restoreDeleted(action) }.getOrDefault(false)
                    if (restored) {
                        val targetIndex = clampIndex(action.fromIndex)
                        setCurrentIndex(targetIndex)
                        persistProgress(targetIndex, action.takenAt)
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_delete_undone
                            )
                        )
                        _events.emit(ViewerEvent.RefreshPhotos)
                        Timber.tag("UI").i("Restored deleted photo %s", action.uri)
                    } else {
                        pushAction(action)
                        _events.emit(
                            ViewerEvent.ShowSnackbar(
                                messageRes = R.string.viewer_snackbar_undo_failed
                            )
                        )
                    }
                    _actionInProgress.value = null
                }
            }
        }
    }

    fun observeUploadEnqueued(photo: PhotoItem?): Flow<Boolean> {
        val current = photo ?: return flowOf(false)
        return combine(
            uploadEnqueuer.isEnqueued(current.uri),
            uploadQueueRepository.observeQueuedOrProcessing(current.id)
        ) { enqueued, queued -> enqueued || queued }
            .distinctUntilChanged()
    }

    private suspend fun finalizeDeletion(pending: PendingDelete) {
        val backup = pending.backup
        if (backup != null) {
            pushAction(
                UserAction.Deleted(
                    uri = pending.documentInfo.uri,
                    originalParent = pending.documentInfo.parentUri,
                    displayName = pending.documentInfo.displayName,
                    mimeType = pending.documentInfo.mimeType,
                    backupPath = backup.path,
                    fromIndex = pending.fromIndex,
                    toIndex = pending.toIndex,
                    takenAt = pending.photo.takenAt
                )
            )
        }
        val targetIndex = if (pending.toIndex != pending.fromIndex) {
            pending.toIndex
        } else {
            clampIndex(pending.fromIndex)
        }
        setCurrentIndex(targetIndex)
        persistProgress(targetIndex, pending.photo.takenAt)
        _events.emit(
            ViewerEvent.ShowSnackbar(
                messageRes = R.string.viewer_snackbar_delete_success,
                withUndo = backup != null
            )
        )
        _events.emit(ViewerEvent.RefreshPhotos)
        Timber.tag("UI").i("Deleted photo %s", pending.documentInfo.uri)
        _actionInProgress.value = null
    }

    private suspend fun finalizePendingBatchMove() {
        val currentState = pendingBatchMove ?: return
        val remainingPhotos = currentState.photos
        if (remainingPhotos.isEmpty()) {
            pendingBatchMove = null
            pendingSingleMove = null
            clearSelection()
            _actionInProgress.value = null
            return
        }
        val singleMoveContext = pendingSingleMove
        var successCount = currentState.successCount
        var lastSuccessUri = currentState.lastSuccessUri
        val photosToProcess = remainingPhotos
        pendingBatchMove = currentState.copy(requestType = null)

        photosToProcess.forEachIndexed { index, photo ->
            when (val result = saFileRepository.moveToProcessing(photo.uri)) {
                is MoveResult.Success -> {
                    successCount += 1
                    lastSuccessUri = result.uri
                    Timber.tag("UI").i("Batch moved photo %s to processing", photo.uri)
                }
                is MoveResult.RequiresWritePermission -> {
                    pendingBatchMove = currentState.copy(
                        photos = photosToProcess.drop(index),
                        successCount = successCount,
                        lastSuccessUri = lastSuccessUri,
                        requestType = MovePermissionType.Write
                    )
                    logPermissionRequest(MovePermissionType.Write, photo.uri)
                    _events.emit(ViewerEvent.RequestWrite(result.pendingIntent.intentSender))
                    return
                }
                is MoveResult.RequiresDeletePermission -> {
                    pendingBatchMove = currentState.copy(
                        photos = photosToProcess.drop(index),
                        successCount = successCount,
                        lastSuccessUri = lastSuccessUri,
                        requestType = MovePermissionType.Delete
                    )
                    logPermissionRequest(MovePermissionType.Delete, photo.uri)
                    _events.emit(ViewerEvent.RequestDelete(result.pendingIntent.intentSender))
                    return
                }
            }
        }

        if (successCount > 0) {
            if (singleMoveContext != null) {
                val processingUri = lastSuccessUri
                    ?: throw IllegalStateException("Missing processing destination for ${singleMoveContext.photo.uri}")
                pushAction(
                    UserAction.MovedToProcessing(
                        fromUri = singleMoveContext.photo.uri,
                        toUri = processingUri,
                        originalParent = singleMoveContext.documentInfo.parentUri,
                        displayName = singleMoveContext.documentInfo.displayName,
                        fromIndex = singleMoveContext.fromIndex,
                        toIndex = singleMoveContext.toIndex
                    )
                )
                if (singleMoveContext.toIndex != singleMoveContext.fromIndex) {
                    setCurrentIndex(singleMoveContext.toIndex)
                }
                persistProgress(singleMoveContext.toIndex, singleMoveContext.photo.takenAt)
                _events.emit(ViewerEvent.ShowToast(R.string.viewer_toast_processing_success))
                Timber.tag("UI").i(
                    "Moved %s to processing (from=%d, to=%d)",
                    singleMoveContext.photo.uri,
                    singleMoveContext.fromIndex,
                    singleMoveContext.toIndex
                )
            } else {
                _events.emit(ViewerEvent.ShowToast(R.string.viewer_toast_processing_success))
                _events.emit(ViewerEvent.RefreshPhotos)
            }
            Timber.tag("UI").i("Batch move completed for %d photos", successCount)
        } else {
            if (singleMoveContext != null) {
                persistUndoStack()
            }
            _events.emit(
                ViewerEvent.ShowSnackbar(
                    messageRes = R.string.viewer_snackbar_processing_failed
                )
            )
        }
        pendingBatchMove = null
        pendingSingleMove = null
        clearSelection()
        _actionInProgress.value = null
    }

    private suspend fun finalizeBatchDelete(photos: List<PhotoItem>) {
        val manualDeletionRequired = !isAtLeastR()
        val successCount = if (manualDeletionRequired) {
            val resolver = context.contentResolver
            var deletedCount = 0
            withContext(Dispatchers.IO) {
                photos.forEach { photo ->
                    runCatching { resolver.delete(photo.uri, null, null) }
                        .onSuccess { deleted ->
                            if (deleted > 0) {
                                deletedCount += 1
                            }
                        }
                        .onFailure { error ->
                            Timber.tag("UI").e(error, "Failed to delete %s during batch", photo.uri)
                        }
                }
            }
            deletedCount
        } else {
            photos.size
        }
        if (!manualDeletionRequired || successCount > 0) {
            _events.emit(
                ViewerEvent.ShowSnackbar(
                    messageRes = R.string.viewer_snackbar_delete_success
                )
            )
            _events.emit(ViewerEvent.RefreshPhotos)
        } else {
            _events.emit(
                ViewerEvent.ShowSnackbar(
                    messageRes = R.string.viewer_snackbar_delete_failed
                )
            )
        }
        pendingBatchDelete = null
        clearSelection()
        _actionInProgress.value = null
    }

    private fun isAtLeastR(): Boolean =
        (buildVersionOverride ?: Build.VERSION.SDK_INT) >= Build.VERSION_CODES.R

    private suspend fun createDeleteBackup(info: DocumentInfo): DeleteBackup? =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val inputStream = resolver.openInputStream(info.uri) ?: return@runCatching null
                val directory = File(context.cacheDir, "viewer-delete-backups")
                if (!directory.exists() && !directory.mkdirs()) {
                    inputStream.close()
                    return@runCatching null
                }
                val sanitizedName = info.displayName.replace('/', '_')
                val backupFile = File(directory, "${UUID.randomUUID()}_$sanitizedName")
                inputStream.use { source ->
                    backupFile.outputStream().use { target ->
                        source.copyTo(target)
                    }
                }
                DeleteBackup(file = backupFile)
            }.getOrNull()
        }

    private suspend fun restoreDeleted(action: UserAction.Deleted): Boolean =
        withContext(Dispatchers.IO) {
            val backupPath = action.backupPath ?: return@withContext false
            val backupFile = File(backupPath)
            if (!backupFile.exists()) {
                return@withContext false
            }
            val parent = DocumentFile.fromTreeUri(context, action.originalParent)
                ?: return@withContext false
            val mimeType = action.mimeType ?: DEFAULT_MIME
            val displayName = generateUniqueDisplayName(parent, action.displayName)
            val destination = parent.createFile(mimeType, displayName) ?: return@withContext false
            val resolver = context.contentResolver
            resolver.openOutputStream(destination.uri)?.use { output ->
                backupFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: return@withContext false
            backupFile.delete()
            true
        }

    private fun generateUniqueDisplayName(
        destinationDirectory: DocumentFile,
        originalDisplayName: String
    ): String {
        val originalComponents = parseDisplayName(originalDisplayName)

        val usedSuffixes = mutableSetOf<Int>()
        var hasExactMatch = false

        destinationDirectory.listFiles().forEach { existing ->
            if (!existing.isFile) {
                return@forEach
            }
            val existingName = existing.name ?: return@forEach
            val existingComponents = parseDisplayName(existingName)
            if (!existingComponents.sharesRootWith(originalComponents)) {
                return@forEach
            }

            if (existingName == originalDisplayName) {
                hasExactMatch = true
            }

            usedSuffixes += existingComponents.suffix ?: 0
        }

        if (!hasExactMatch) {
            return originalDisplayName
        }

        var candidateSuffix = max(originalComponents.nextSuffixCandidate, 1)
        while (usedSuffixes.contains(candidateSuffix)) {
            candidateSuffix += 1
        }

        return buildDisplayName(originalComponents.baseRoot, originalComponents.extension, candidateSuffix)
    }

    private fun parseDisplayName(displayName: String): DisplayNameComponents {
        val (baseName, extension) = splitExtension(displayName)
        val lastHyphenIndex = baseName.lastIndexOf('-')
        if (lastHyphenIndex > 0 && lastHyphenIndex + 1 < baseName.length) {
            val suffixCandidate = baseName.substring(lastHyphenIndex + 1)
            val suffix = suffixCandidate.toIntOrNull()
            if (suffix != null) {
                val baseRoot = baseName.substring(0, lastHyphenIndex)
                if (baseRoot.isNotEmpty()) {
                    return DisplayNameComponents(
                        baseRoot = baseRoot,
                        extension = extension,
                        suffix = suffix,
                        nextSuffixCandidate = suffix + 1
                    )
                }
            }
        }

        return DisplayNameComponents(
            baseRoot = baseName,
            extension = extension,
            suffix = null,
            nextSuffixCandidate = 1
        )
    }

    private fun splitExtension(displayName: String): Pair<String, String?> {
        val lastDot = displayName.lastIndexOf('.')
        if (lastDot > 0 && lastDot + 1 < displayName.length) {
            val base = displayName.substring(0, lastDot)
            val extension = displayName.substring(lastDot + 1)
            return base to extension
        }
        return displayName to null
    }

    private fun buildDisplayName(base: String, extension: String?, suffix: Int?): String {
        val baseWithSuffix = when (suffix) {
            null -> base
            0 -> base
            else -> "$base-$suffix"
        }
        return if (extension.isNullOrEmpty()) {
            baseWithSuffix
        } else {
            "$baseWithSuffix.$extension"
        }
    }

    private data class DisplayNameComponents(
        val baseRoot: String,
        val extension: String?,
        val suffix: Int?,
        val nextSuffixCandidate: Int
    ) {
        fun sharesRootWith(other: DisplayNameComponents): Boolean {
            return baseRoot == other.baseRoot && extension == other.extension
        }
    }

    private suspend fun loadDocumentInfo(uri: Uri): DocumentInfo = withContext(Dispatchers.IO) {
        val folder = folderRepository.getFolder()
            ?: throw IllegalStateException("Root folder is not selected")
        val treeUri = Uri.parse(folder.treeUri)
        val resolver = context.contentResolver

        if (uri.authority == MediaStore.AUTHORITY) {
            loadMediaStoreDocumentInfo(resolver, uri, treeUri)
        } else {
            loadSafDocumentInfo(resolver, uri, treeUri)
        }
    }

    private fun loadSafDocumentInfo(
        resolver: android.content.ContentResolver,
        uri: Uri,
        treeUri: Uri
    ): DocumentInfo {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID
        )
        val cursor = resolver.query(uri, projection, null, null, null)
            ?: throw IllegalStateException("Unable to query document $uri")
        cursor.use { result ->
            if (!result.moveToFirst()) {
                throw IllegalStateException("Unable to read document info for $uri")
            }
            val displayNameIndex = result.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = result.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val lastModifiedIndex = result.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val mimeIndex = result.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val documentIdIndex = result.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)

            val name = result.getString(displayNameIndex) ?: DEFAULT_FILE_NAME
            val size = if (result.isNull(sizeIndex)) {
                null
            } else {
                result.getLong(sizeIndex)
            }
            val lastModified = if (result.isNull(lastModifiedIndex)) {
                null
            } else {
                result.getLong(lastModifiedIndex)
            }
            val mimeType = if (result.isNull(mimeIndex)) {
                null
            } else {
                result.getString(mimeIndex)
            }
            val documentId = result.getString(documentIdIndex)
            val parentDocumentId = resolveSafParentDocumentId(treeUri, documentId)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
            return DocumentInfo(
                uri = uri,
                displayName = name,
                parentUri = parentUri,
                size = size,
                lastModified = lastModified,
                mimeType = mimeType
            )
        }
    }

    private fun loadMediaStoreDocumentInfo(
        resolver: android.content.ContentResolver,
        uri: Uri,
        treeUri: Uri
    ): DocumentInfo {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val cursor = resolver.query(uri, projection, null, null, null)
            ?: throw IllegalStateException("Unable to query document $uri")
        cursor.use { result ->
            if (!result.moveToFirst()) {
                throw IllegalStateException("Unable to read document info for $uri")
            }
            val displayNameIndex = result.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIndex = result.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateModifiedIndex = result.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val dateTakenIndex = result.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
            val relativePathIndex = result.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val mimeIndex = result.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

            val name = result.getString(displayNameIndex) ?: DEFAULT_FILE_NAME
            val size = if (sizeIndex >= 0 && !result.isNull(sizeIndex)) {
                result.getLong(sizeIndex)
            } else {
                null
            }
            val dateModifiedSeconds = if (dateModifiedIndex >= 0 && !result.isNull(dateModifiedIndex)) {
                result.getLong(dateModifiedIndex)
            } else {
                null
            }
            val dateTakenMillis = if (dateTakenIndex >= 0 && !result.isNull(dateTakenIndex)) {
                result.getLong(dateTakenIndex)
            } else {
                null
            }
            val lastModified = when {
                dateModifiedSeconds != null && dateModifiedSeconds > 0 ->
                    java.util.concurrent.TimeUnit.SECONDS.toMillis(dateModifiedSeconds)
                dateTakenMillis != null && dateTakenMillis > 0 -> dateTakenMillis
                else -> null
            }
            val relativePath = if (relativePathIndex >= 0 && !result.isNull(relativePathIndex)) {
                result.getString(relativePathIndex)
            } else {
                null
            }
            val parentDocumentId = resolveMediaStoreParentDocumentId(treeUri, relativePath)
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
            return DocumentInfo(
                uri = uri,
                displayName = name,
                parentUri = parentUri,
                size = size,
                lastModified = lastModified,
                mimeType = if (mimeIndex >= 0 && !result.isNull(mimeIndex)) {
                    result.getString(mimeIndex)
                } else {
                    null
                }
            )
        }
    }

    private fun resolveSafParentDocumentId(treeUri: Uri, documentId: String?): String {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        if (documentId.isNullOrEmpty() || documentId == treeDocumentId) {
            return treeDocumentId
        }
        val slashIndex = documentId.lastIndexOf('/')
        if (slashIndex > 0) {
            return documentId.substring(0, slashIndex)
        }
        return treeDocumentId
    }

    private fun resolveMediaStoreParentDocumentId(treeUri: Uri, relativePath: String?): String {
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        if (relativePath.isNullOrEmpty()) {
            return treeDocumentId
        }
        val sanitizedPath = relativePath.trimEnd('/')
        if (sanitizedPath.isEmpty()) {
            return treeDocumentId
        }
        val volume = treeDocumentId.substringBefore(':', missingDelimiterValue = treeDocumentId)
        val treePath = treeDocumentId.substringAfter(':', missingDelimiterValue = "")
        val candidate = when {
            treePath.isEmpty() -> sanitizedPath
            sanitizedPath == treePath -> sanitizedPath
            sanitizedPath.startsWith("$treePath/") -> sanitizedPath
            else -> treePath
        }
        return if (candidate.isEmpty()) {
            treeDocumentId
        } else {
            "$volume:$candidate"
        }
    }

    private suspend fun persistProgress(index: Int, anchor: Instant?) {
        val folderId = folderId.value ?: return
        val normalizedIndex = index.coerceAtLeast(0)
        anchorDate.value = anchor
        reviewProgressStore.savePosition(folderId, normalizedIndex, anchor)
    }

    private fun clampIndex(index: Int): Int {
        return clampToCount(index, photoCount.value)
    }

    private fun clampToCount(index: Int, count: Int): Int {
        if (count <= 0) {
            return 0
        }
        return index.coerceIn(0, count - 1)
    }

    private fun computeNextIndex(current: Int): Int {
        val count = photoCount.value
        if (count <= 0) {
            return 0
        }
        return (current + 1).coerceAtMost(count - 1)
    }

    fun updateVisiblePhoto(totalCount: Int, photo: PhotoItem?) {
        photoCount.value = totalCount
        currentPhoto.value = photo
    }

    private fun pushAction(action: UserAction) {
        undoStack.addLast(action)
        persistUndoStack()
    }

    private fun persistUndoStack() {
        _undoCount.value = undoStack.size
        val states = ArrayList<UndoEntryState>(undoStack.size)
        undoStack.forEach { action ->
            states.add(action.toState())
        }
        savedStateHandle[undoStackKey] = states
    }

    private fun restoreUndoStack() {
        val saved = savedStateHandle.get<ArrayList<UndoEntryState>>(undoStackKey)
        if (saved.isNullOrEmpty()) {
            _undoCount.value = 0
            return
        }
        saved.mapNotNull { state ->
            runCatching { state.toUserAction() }.getOrNull()
        }.forEach { action ->
            undoStack.addLast(action)
        }
        _undoCount.value = undoStack.size
    }

    private fun buildIdempotencyKey(info: DocumentInfo): String {
        val base = if (info.size != null && info.size >= 0 && info.lastModified != null && info.lastModified > 0) {
            "${info.uri}|${info.size}|${info.lastModified}"
        } else {
            info.uri.toString()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(base.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    sealed interface ViewerEvent {
        data class ShowSnackbar(
            @StringRes val messageRes: Int,
            val withUndo: Boolean = false
        ) : ViewerEvent
        data class ShowToast(@StringRes val messageRes: Int) : ViewerEvent
        data class RequestDelete(val intentSender: IntentSender) : ViewerEvent
        data class RequestWrite(val intentSender: IntentSender) : ViewerEvent
        data object RefreshPhotos : ViewerEvent
    }

    sealed interface UserAction {
        data class Skip(val fromIndex: Int, val toIndex: Int) : UserAction
        data class MovedToProcessing(
            val fromUri: Uri,
            val toUri: Uri,
            val originalParent: Uri,
            val displayName: String,
            val fromIndex: Int,
            val toIndex: Int
        ) : UserAction
        data class EnqueuedUpload(
            val uri: Uri,
            val fromIndex: Int,
            val toIndex: Int
        ) : UserAction
        data class Deleted(
            val uri: Uri,
            val originalParent: Uri,
            val displayName: String,
            val mimeType: String?,
            val backupPath: String?,
            val fromIndex: Int,
            val toIndex: Int,
            val takenAt: Instant?
        ) : UserAction
    }

    enum class ViewerActionInProgress {
        Processing,
        Upload,
        Delete
    }

    enum class DeleteResult {
        Success,
        Cancelled,
        Failed
    }

    private data class DocumentInfo(
        val uri: Uri,
        val displayName: String,
        val parentUri: Uri,
        val size: Long?,
        val lastModified: Long?,
        val mimeType: String?
    )

    private data class PendingDelete(
        val photo: PhotoItem,
        val documentInfo: DocumentInfo,
        val backup: DeleteBackup?,
        val fromIndex: Int,
        val toIndex: Int
    )

    private data class PendingBatchDelete(
        val photos: List<PhotoItem>
    )

    private data class PendingBatchMove(
        val photos: List<PhotoItem>,
        val successCount: Int = 0,
        val lastSuccessUri: Uri? = null,
        val requestType: MovePermissionType? = null
    )

    private data class PendingSingleMove(
        val photo: PhotoItem,
        val documentInfo: DocumentInfo,
        val fromIndex: Int,
        val toIndex: Int
    )

    private enum class MovePermissionType { Write, Delete }

    private data class DeleteBackup(val file: File) {
        val path: String = file.absolutePath

        fun delete() {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private data class UndoEntryState(
        val type: UserActionType,
        val fromIndex: Int,
        val toIndex: Int,
        val fromUri: String?,
        val toUri: String?,
        val originalParent: String?,
        val displayName: String?,
        val mimeType: String?,
        val backupPath: String?,
        val takenAt: Long?
    ) : Serializable

    private enum class UserActionType : Serializable {
        Skip,
        MovedToProcessing,
        EnqueuedUpload,
        Deleted
    }

    private fun UserAction.toState(): UndoEntryState = when (this) {
        is UserAction.Skip -> UndoEntryState(
            type = UserActionType.Skip,
            fromIndex = fromIndex,
            toIndex = toIndex,
            fromUri = null,
            toUri = null,
            originalParent = null,
            displayName = null,
            mimeType = null,
            backupPath = null,
            takenAt = null
        )
        is UserAction.MovedToProcessing -> UndoEntryState(
            type = UserActionType.MovedToProcessing,
            fromIndex = fromIndex,
            toIndex = toIndex,
            fromUri = fromUri.toString(),
            toUri = toUri.toString(),
            originalParent = originalParent.toString(),
            displayName = displayName,
            mimeType = null,
            backupPath = null,
            takenAt = null
        )
        is UserAction.EnqueuedUpload -> UndoEntryState(
            type = UserActionType.EnqueuedUpload,
            fromIndex = fromIndex,
            toIndex = toIndex,
            fromUri = uri.toString(),
            toUri = null,
            originalParent = null,
            displayName = null,
            mimeType = null,
            backupPath = null,
            takenAt = null
        )
        is UserAction.Deleted -> UndoEntryState(
            type = UserActionType.Deleted,
            fromIndex = fromIndex,
            toIndex = toIndex,
            fromUri = uri.toString(),
            toUri = null,
            originalParent = originalParent.toString(),
            displayName = displayName,
            mimeType = mimeType,
            backupPath = backupPath,
            takenAt = takenAt?.toEpochMilli()
        )
    }

    private fun UndoEntryState.toUserAction(): UserAction = when (type) {
        UserActionType.Skip -> UserAction.Skip(
            fromIndex = fromIndex,
            toIndex = toIndex
        )
        UserActionType.MovedToProcessing -> UserAction.MovedToProcessing(
            fromUri = Uri.parse(requireNotNull(fromUri)),
            toUri = Uri.parse(requireNotNull(toUri)),
            originalParent = Uri.parse(requireNotNull(originalParent)),
            displayName = requireNotNull(displayName),
            fromIndex = fromIndex,
            toIndex = toIndex
        )
        UserActionType.EnqueuedUpload -> UserAction.EnqueuedUpload(
            uri = Uri.parse(requireNotNull(fromUri)),
            fromIndex = fromIndex,
            toIndex = toIndex
        )
        UserActionType.Deleted -> UserAction.Deleted(
            uri = Uri.parse(requireNotNull(fromUri)),
            originalParent = Uri.parse(requireNotNull(originalParent)),
            displayName = requireNotNull(displayName),
            mimeType = mimeType,
            backupPath = backupPath,
            fromIndex = fromIndex,
            toIndex = toIndex,
            takenAt = takenAt?.let(Instant::ofEpochMilli)
        )
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "photo.jpg"
        private const val DEFAULT_MIME = "image/jpeg"
        private const val UI_TAG = "UI"
        private const val PERMISSION_TAG = "Permissions"

        internal var buildVersionOverride: Int? = null
    }
}
