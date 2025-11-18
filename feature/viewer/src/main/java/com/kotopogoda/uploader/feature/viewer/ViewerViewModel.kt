package com.kotopogoda.uploader.feature.viewer

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.media.ExifInterface
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.StringRes
import androidx.core.net.toUri
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
import com.kotopogoda.uploader.core.data.deletion.DeletionQueueRepository
import com.kotopogoda.uploader.core.data.deletion.DeletionRequest
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.data.upload.UploadEnqueueOptions
import com.kotopogoda.uploader.core.data.upload.UploadEnhancementInfo
import com.kotopogoda.uploader.core.data.upload.UploadEnhancementMetrics
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.data.upload.contentSha256FromIdempotencyKey
import com.kotopogoda.uploader.core.data.upload.idempotencyKeyFromContentSha256
import com.kotopogoda.uploader.core.data.util.Hashing
import com.kotopogoda.uploader.core.logging.structuredLog
import com.kotopogoda.uploader.core.data.util.logUriReadDebug
import com.kotopogoda.uploader.core.data.util.requireOriginalIfNeeded
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import com.kotopogoda.uploader.core.settings.reviewProgressFolderId
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceLogging
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.Serializable
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayList
import java.util.Locale
import java.util.LinkedHashMap
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.collections.ArrayDeque
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.system.measureTimeMillis
import timber.log.Timber

@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val folderRepository: FolderRepository,
    private val saFileRepository: SaFileRepository,
    private val uploadEnqueuer: UploadEnqueuer,
    private val uploadQueueRepository: UploadQueueRepository,
    private val deletionQueueRepository: DeletionQueueRepository,
    private val reviewProgressStore: ReviewProgressStore,
    @ApplicationContext private val context: Context,
    private val nativeEnhanceAdapter: com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceAdapter?,
    private val settingsRepository: com.kotopogoda.uploader.core.settings.SettingsRepository,
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
    private var pendingDeletionInitialized: Boolean = false

    private val _undoCount = MutableStateFlow(0)
    val undoCount: StateFlow<Int> = _undoCount.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val initialIndexRestored = MutableStateFlow(false)
    private var pendingInitialIndex: Int? = null

    private val _actionInProgress = MutableStateFlow<ViewerActionInProgress?>(null)
    val actionInProgress: StateFlow<ViewerActionInProgress?> = _actionInProgress.asStateFlow()

    private val _events = MutableSharedFlow<ViewerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ViewerEvent> = _events.asSharedFlow()

    private val _selection = MutableStateFlow<Set<PhotoItem>>(emptySet())
    val selection: StateFlow<Set<PhotoItem>> = _selection.asStateFlow()

    private val pendingDeletionIds = MutableStateFlow<Set<Long>>(emptySet())

    val isSelectionMode: StateFlow<Boolean> = selection
        .map { it.isNotEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = _selection.value.isNotEmpty()
        )

    private val _enhancementState = MutableStateFlow(EnhancementState())
    val enhancementState: StateFlow<EnhancementState> = _enhancementState.asStateFlow()

    private val _isEnhancementAvailable = MutableStateFlow(false)
    val isEnhancementAvailable: StateFlow<Boolean> = _isEnhancementAvailable.asStateFlow()
    private var enhancementJob: Job? = null
    private var pendingDelete: PendingDelete? = null
    private var pendingBatchDelete: PendingBatchDelete? = null
    private var pendingBatchMove: PendingBatchMove? = null
    private var pendingSingleMove: PendingSingleMove? = null
    private val pendingCleanupJobs = mutableMapOf<String, Job>()
    @Volatile
    private var autoDeleteEnabled: Boolean = false

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

    private fun logDeletionQueueEvent(
        stage: String,
        mediaId: Long?,
        uri: Uri?,
        displayName: String?,
        outcome: String,
        alreadyEnqueued: Boolean,
        throwable: Throwable? = null,
    ) {
        val attributes = mutableListOf<Pair<String, Any?>>(
            "phase" to "enqueue",
            "source" to "user_delete",
            "stage" to stage,
            "reason" to QUEUE_DELETE_REASON,
            "already_enqueued" to alreadyEnqueued,
            "setting_enabled" to autoDeleteEnabled,
            "outcome" to outcome,
        )
        mediaId?.let { attributes.add("media_id" to it) }
        uri?.let { attributes.add("uri" to it.toString()) }
        displayName?.let { attributes.add("display_name" to it) }
        val message = structuredLog(*attributes.toTypedArray())
        when {
            throwable != null -> Timber.tag(DELETION_QUEUE_TAG).e(throwable, message)
            outcome == "duplicate" || outcome == "already_enqueued" -> Timber.tag(DELETION_QUEUE_TAG).w(message)
            else -> Timber.tag(DELETION_QUEUE_TAG).i(message)
        }
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
            settingsRepository.flow.collect { settings ->
                autoDeleteEnabled = settings.autoDeleteAfterUpload
                val adapter = nativeEnhanceAdapter
                if (adapter != null) {
                    runCatching {
                        adapter.initialize(settings.previewQuality)
                    }.onSuccess {
                        _isEnhancementAvailable.value = true
                    }.onFailure { error ->
                        _isEnhancementAvailable.value = false
                        Timber.tag(LOG_TAG).e(error, "Не удалось инициализировать NativeEnhanceAdapter")
                    }
                } else {
                    _isEnhancementAvailable.value = false
                    Timber.tag(LOG_TAG).w("NativeEnhanceAdapter is null, enhancement disabled")
                }
            }
        }

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

        viewModelScope.launch {
            deletionQueueRepository.observePending()
                .map { items -> items.map { it.mediaId }.toSet() }
                .distinctUntilChanged()
                .collect { ids ->
                    pendingDeletionIds.value = ids
                    pendingDeletionInitialized = true
                    updateCanUndo()
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
            var index: Int?
            val elapsed = measureTimeMillis {
                index = photoRepository.findIndexAtOrAfter(startOfDay, endOfDay)
            }
            val resolvedIndex = index
            val resultDetails = if (resolvedIndex == null) {
                arrayOf(
                    "duration_ms" to elapsed,
                    "count_in_day" to 0,
                )
            } else {
                arrayOf(
                    "duration_ms" to elapsed,
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

    fun onEnhancementStrengthChange(value: Float) {
        val clamped = value.coerceIn(MIN_ENHANCEMENT_STRENGTH, MAX_ENHANCEMENT_STRENGTH)
        val currentState = _enhancementState.value

        if (!_isEnhancementAvailable.value) {
            return
        }

        if (clamped == 0f) {
            cancelEnhancementJob()
            disposeEnhancementResult(currentState.result)
            _enhancementState.update { state ->
                state.copy(
                    strength = 0f,
                    inProgress = false,
                    isResultReady = true,
                    progressByTile = emptyMap(),
                    result = null,
                    resultUri = null,
                    resultPhotoId = null,
                    isResultForCurrentPhoto = false,
                )
            }
            return
        }

        if (currentState.isResultReady && currentState.isResultForCurrentPhoto) {
            val result = currentState.result
            if (result != null && strengthsEqual(result.strength, clamped)) {
                _enhancementState.update { state ->
                    state.copy(strength = clamped)
                }
            } else {
                disposeEnhancementResult(result)
                _enhancementState.update { state ->
                    state.copy(
                        strength = clamped,
                        inProgress = false,
                        isResultReady = false,
                        progressByTile = emptyMap(),
                        result = null,
                        resultUri = null,
                        resultPhotoId = null,
                        isResultForCurrentPhoto = false,
                    )
                }
            }
            return
        }

        cancelEnhancementJob()
        disposeEnhancementResult(currentState.result)
        _enhancementState.update { state ->
            state.copy(
                strength = clamped,
                inProgress = false,
                isResultReady = false,
                progressByTile = emptyMap(),
                result = null,
                resultUri = null,
                resultPhotoId = null,
                isResultForCurrentPhoto = false,
            )
        }
    }

    fun onEnhancementStrengthChangeFinished() {
        val target = currentPhoto.value ?: return
        val currentState = _enhancementState.value

        if (!_isEnhancementAvailable.value) {
            return
        }

        if (currentState.strength == 0f) {
            return
        }

        val result = currentState.result
        if (result != null && currentState.isResultReady && currentState.isResultForCurrentPhoto) {
            if (strengthsEqual(result.strength, currentState.strength)) {
                return
            }
        }

        startEnhancementJob(target, currentState.strength)
    }

    fun onEnhancementUnavailableInteraction() {
        viewModelScope.launch {
            _events.emit(
                ViewerEvent.ShowToast(
                    messageRes = R.string.viewer_improve_unavailable_hint
                )
            )
        }
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
                val enhancementSnapshot = _enhancementState.value
                val enhancementResult = enhancementSnapshot.result
                val normalizedStrength = enhancementSnapshot.strength
                    .coerceIn(MIN_ENHANCEMENT_STRENGTH, MAX_ENHANCEMENT_STRENGTH)
                val resultStrength = enhancementResult?.strength
                    ?.coerceIn(MIN_ENHANCEMENT_STRENGTH, MAX_ENHANCEMENT_STRENGTH)
                val matchesCurrentPhoto = enhancementSnapshot.resultPhotoId == current.id
                val useEnhancedResult = enhancementSnapshot.isResultReady && matchesCurrentPhoto && enhancementResult != null
                val publishStrength = if (useEnhancedResult && resultStrength != null) {
                    resultStrength
                } else {
                    normalizedStrength
                }
                val publishDetails = mutableListOf(
                    "has_result" to (enhancementResult != null),
                    "matches_photo" to matchesCurrentPhoto,
                    "enhanced" to useEnhancedResult,
                    "strength" to "%.2f".format(publishStrength),
                    "cleanup_source" to true,
                    "cleanup_result" to useEnhancedResult,
                    "cleanup_strategy" to "post_upload",
                )
                enhancementResult?.let { publishDetails += "delegate" to it.delegate.name.lowercase() }
                logEnhancement(
                    action = "publish_click",
                    photo = current,
                    *publishDetails.filterNot { it.second == null }.toTypedArray(),
                )

                val enqueueUri: Uri
                val idempotencyKey: String
                val contentSha256: String
                val enqueueOptions: UploadEnqueueOptions
                if (useEnhancedResult && enhancementResult != null) {
                    val digest = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(10_000L) {
                            Hashing.sha256(context.contentResolver, enhancementResult.uri)
                        }
                    } ?: throw IOException("Timed out computing digest for ${enhancementResult.uri}")
                    val delegateName = enhancementResult.delegate.name.lowercase()
                    val metrics = enhancementResult.metrics
                    val metricsPayload = UploadEnhancementMetrics(
                        lMean = metrics.lMean.toFloat(),
                        pDark = metrics.pDark.toFloat(),
                        bSharpness = metrics.bSharpness.toFloat(),
                        nNoise = metrics.nNoise.toFloat(),
                    )
                    val fileSize = enhancementResult.file.length().takeIf { it > 0 }
                    enqueueUri = enhancementResult.uri
                    idempotencyKey = idempotencyKeyFromContentSha256(digest)
                    contentSha256 = digest
                    val uploadEnhancement = enhancementResult.uploadInfo?.copy(
                        strength = publishStrength,
                        delegate = enhancementResult.uploadInfo.delegate.ifBlank { delegateName },
                        fileSize = fileSize ?: enhancementResult.uploadInfo.fileSize,
                    ) ?: UploadEnhancementInfo(
                        strength = publishStrength,
                        delegate = delegateName,
                        metrics = metricsPayload,
                        fileSize = fileSize,
                    )
                    enqueueOptions = UploadEnqueueOptions(
                        photoId = current.id,
                        overrideDisplayName = documentInfo.displayName,
                        overrideSize = fileSize ?: documentInfo.size,
                        enhancement = uploadEnhancement,
                    )
                } else {
                    val builtKey = buildIdempotencyKey(documentInfo)
                    val digest = contentSha256FromIdempotencyKey(builtKey)
                        ?: throw IllegalStateException("Invalid idempotency key format: $builtKey")
                    enqueueUri = current.uri
                    idempotencyKey = builtKey
                    contentSha256 = digest
                    enqueueOptions = UploadEnqueueOptions(
                        photoId = current.id,
                        overrideDisplayName = documentInfo.displayName,
                        overrideSize = documentInfo.size,
                    )
                }
                uploadEnqueuer.enqueue(
                    uri = enqueueUri,
                    idempotencyKey = idempotencyKey,
                    displayName = documentInfo.displayName,
                    contentSha256 = contentSha256,
                    options = enqueueOptions,
                )
                val cleanupRequest = PendingUploadCleanup(
                    photo = current,
                    documentInfo = documentInfo,
                    enhancementResult = enhancementResult.takeIf { useEnhancedResult },
                    useEnhancedResult = useEnhancedResult,
                    idempotencyKey = idempotencyKey,
                    enqueueUri = enqueueUri,
                )
                if (useEnhancedResult && enhancementResult != null) {
                    logEnhancement(
                        action = "enhance_result_publish",
                        photo = current,
                        "delegate" to enhancementResult.delegate.name.lowercase(),
                        "strength" to "%.2f".format(publishStrength),
                        "sha256" to contentSha256,
                        "size" to (enqueueOptions.overrideSize ?: enhancementResult.file.length()),
                        "tile_used" to enhancementResult.pipeline.tileUsed,
                        "seam_max_delta" to enhancementResult.pipeline.seamMaxDelta.format3(),
                        "seam_mean_delta" to enhancementResult.pipeline.seamMeanDelta.format3(),
                        "seam_area" to enhancementResult.pipeline.seamArea,
                        "seam_zero_area" to enhancementResult.pipeline.seamZeroArea,
                        "seam_min_weight" to enhancementResult.pipeline.seamMinWeight.format3(),
                        "seam_max_weight" to enhancementResult.pipeline.seamMaxWeight.format3(),
                    )
                    disposeEnhancementResult(
                        enhancementResult,
                        EnhancementResultDisposition.ENQUEUED,
                    )
                    _enhancementState.update { state ->
                        state.copy(
                            result = null,
                            resultUri = null,
                            resultPhotoId = null,
                            isResultForCurrentPhoto = false,
                        )
                    }
                }
                scheduleUploadCleanup(cleanupRequest)
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

    fun onEnqueueDeletion(photo: PhotoItem?) {
        if (_actionInProgress.value != null) {
            return
        }
        val current = photo ?: return
        val mediaId = current.id.toLongOrNull()
        if (mediaId == null) {
            viewModelScope.launch {
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_delete_failed
                    )
                )
            }
            return
        }
        if (mediaId in pendingDeletionIds.value) {
            logDeletionQueueEvent(
                stage = "dedupe",
                mediaId = mediaId,
                uri = current.uri,
                displayName = null,
                outcome = "already_enqueued",
                alreadyEnqueued = true,
            )
            return
        }
        logUi(
            category = "UI/DELETE_QUEUE",
            action = "enqueue_request",
            uri = current.uri,
            "media_id" to mediaId,
            "current_index" to currentIndex.value,
        )
        viewModelScope.launch {
            var documentInfo: DocumentInfo? = null
            try {
                documentInfo = loadDocumentInfo(current.uri)
                val info = requireNotNull(documentInfo)
                val fromIndex = currentIndex.value
                val toIndex = computeNextIndex(fromIndex)
                val request = DeletionRequest(
                    mediaId = mediaId,
                    contentUri = info.uri.toString(),
                    displayName = info.displayName,
                    sizeBytes = info.size,
                    dateTaken = current.takenAt?.toEpochMilli(),
                    reason = QUEUE_DELETE_REASON
                )
                val inserted = deletionQueueRepository.enqueue(listOf(request))
                if (inserted <= 0) {
                    logDeletionQueueEvent(
                        stage = "enqueue",
                        mediaId = mediaId,
                        uri = info.uri,
                        displayName = info.displayName,
                        outcome = "duplicate",
                        alreadyEnqueued = true,
                    )
                    logUi(
                        category = "UI/DELETE_QUEUE",
                        action = "enqueue_skipped",
                        uri = current.uri,
                        "media_id" to mediaId
                    )
                    return@launch
                }
                logDeletionQueueEvent(
                    stage = "enqueue",
                    mediaId = mediaId,
                    uri = info.uri,
                    displayName = info.displayName,
                    outcome = "enqueued",
                    alreadyEnqueued = false,
                )
                pendingDeletionIds.update { ids -> ids + mediaId }
                updateCanUndo()
                pushAction(
                    UserAction.QueuedDeletion(
                        mediaId = mediaId,
                        uri = current.uri,
                        fromIndex = fromIndex,
                        toIndex = toIndex
                    )
                )
                if (toIndex != fromIndex) {
                    setCurrentIndex(toIndex)
                }
                persistProgress(toIndex, current.takenAt)
                _events.emit(ViewerEvent.ShowToast(R.string.viewer_toast_deletion_enqueued))
                logUi(
                    category = "UI/DELETE_QUEUE",
                    action = "enqueue_success",
                    uri = current.uri,
                    "media_id" to mediaId,
                    "from_index" to fromIndex,
                    "to_index" to toIndex
                )
            } catch (error: Exception) {
                logDeletionQueueEvent(
                    stage = "enqueue",
                    mediaId = mediaId,
                    uri = documentInfo?.uri ?: current.uri,
                    displayName = documentInfo?.displayName,
                    outcome = "error",
                    alreadyEnqueued = false,
                    throwable = error,
                )
                logUiError(
                    category = "UI/DELETE_QUEUE",
                    action = "enqueue_failure",
                    error = error,
                    uri = current.uri,
                )
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_delete_failed
                    )
                )
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
                        mediaStoreDeleteRequestFactory(
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
            uri = null,
            "count" to photos.size,
        )
        viewModelScope.launch {
            _actionInProgress.value = ViewerActionInProgress.Delete
            try {
                if (isAtLeastR()) {
                    val pendingIntent = withContext(Dispatchers.IO) {
                        mediaStoreDeleteRequestFactory(
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
            is UserAction.QueuedDeletion -> {
                viewModelScope.launch {
                    fun logUndoUnavailable(reason: String) {
                        logUi(
                            category = "UI/DELETE_QUEUE",
                            action = "undo_unavailable",
                            uri = action.uri,
                            "media_id" to action.mediaId,
                            "reason" to reason,
                        )
                    }

                    _actionInProgress.value = ViewerActionInProgress.Delete
                    val mediaId = action.mediaId

                    if (mediaId !in pendingDeletionIds.value) {
                        logUndoUnavailable("not_pending")
                        _actionInProgress.value = null
                        return@launch
                    }

                    try {
                        val removed = runCatching {
                            deletionQueueRepository.markSkipped(listOf(mediaId))
                        }.onFailure { error ->
                            logUiError(
                                category = "UI/DELETE_QUEUE",
                                action = "undo_failure",
                                error = error,
                                uri = action.uri,
                            )
                        }.getOrDefault(0)
                        if (removed > 0) {
                            pendingDeletionIds.update { ids -> ids - mediaId }
                            updateCanUndo()
                            val targetIndex = clampIndex(action.fromIndex)
                            setCurrentIndex(targetIndex)
                            logUi(
                                category = "UI/DELETE_QUEUE",
                                action = "undo_success",
                                uri = action.uri,
                                "media_id" to mediaId,
                                "from_index" to action.fromIndex,
                                "to_index" to action.toIndex
                            )
                            _events.emit(
                                ViewerEvent.ShowSnackbar(
                                    messageRes = R.string.viewer_snackbar_delete_undone
                                )
                            )
                        } else {
                            logUi(
                                category = "UI/DELETE_QUEUE",
                                action = "undo_skipped",
                                uri = action.uri,
                                "media_id" to mediaId
                            )
                            pushAction(action)
                            _events.emit(
                                ViewerEvent.ShowSnackbar(
                                    messageRes = R.string.viewer_snackbar_undo_failed
                                )
                            )
                        }
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

    fun observeDeletionQueued(photo: PhotoItem?): Flow<Boolean> {
        val mediaId = photo?.id?.toLongOrNull() ?: return flowOf(false)
        return pendingDeletionIds
            .map { ids -> mediaId in ids }
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
                val normalizedUri = resolver.requireOriginalIfNeeded(info.uri)
                resolver.logUriReadDebug("ViewerViewModel.backup", info.uri, normalizedUri)
                val inputStream = resolver.openInputStream(normalizedUri) ?: return@runCatching null
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
        if (photo != currentPhoto.value) {
            cancelEnhancementJob(resetToReady = true)
        }
        currentPhoto.value = photo
        _enhancementState.update { state ->
            val matches = state.resultPhotoId != null && state.resultPhotoId == photo?.id
            state.copy(
                isResultForCurrentPhoto = matches,
                resultUri = state.resultUri.takeIf { matches },
            )
        }
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
        updateCanUndo()
    }

    private fun updateCanUndo() {
        var trimmed = false
        while (true) {
            val lastAction = undoStack.lastOrNull() ?: break
            if (isActionActionable(lastAction)) {
                break
            }
            undoStack.removeLast()
            trimmed = true
        }
        if (trimmed) {
            _undoCount.value = undoStack.size
            val states = ArrayList<UndoEntryState>(undoStack.size)
            undoStack.forEach { action ->
                states.add(action.toState())
            }
            savedStateHandle[undoStackKey] = states
        }
        _canUndo.value = undoStack.isNotEmpty()
    }

    private fun isActionActionable(action: UserAction): Boolean {
        return when (action) {
            is UserAction.QueuedDeletion -> if (!pendingDeletionInitialized) {
                true
            } else {
                action.mediaId in pendingDeletionIds.value
            }
            else -> true
        }
    }

    private fun startEnhancementJob(photo: PhotoItem, strength: Float) {
        cancelEnhancementJob()
        disposeEnhancementResult(_enhancementState.value.result)
        val job = viewModelScope.launch {
            val normalized = strength.coerceIn(MIN_ENHANCEMENT_STRENGTH, MAX_ENHANCEMENT_STRENGTH)
            _enhancementState.update {
                it.copy(
                    inProgress = true,
                    isResultReady = false,
                    progressByTile = emptyMap(),
                    result = null,
                    resultUri = null,
                    resultPhotoId = null,
                    isResultForCurrentPhoto = false,
                )
            }
            val workspace = try {
                createEnhancementWorkspace(photo)
            } catch (error: Exception) {
                Timber.tag(UI_TAG).e(error, "Failed to prepare enhancement workspace for %s", photo.uri)
                _enhancementState.update { state ->
                    state.copy(
                        inProgress = false,
                        isResultReady = false,
                        progressByTile = emptyMap(),
                        result = null,
                        resultUri = null,
                        resultPhotoId = null,
                        isResultForCurrentPhoto = false,
                    )
                }
                return@launch
            }
            var producedResult: EnhancementResult? = null
            try {
                val analysis = try {
                    analyzeWorkspace(workspace)
                } catch (error: Exception) {
                    Timber.tag(UI_TAG).w(error, "Failed to analyze enhancement workspace for %s", photo.uri)
                    WorkspaceAnalysis(
                        metrics = EnhanceEngine.Metrics(0.0, 0.0, 0.0, 0.0),
                        width = 0,
                        height = 0,
                    )
                }
                val metrics = analysis.metrics
                val tileSize = DEFAULT_ENHANCE_TILE_SIZE
                val tileOverlap = DEFAULT_ENHANCE_TILE_OVERLAP
                val totalTiles = computeTileCount(analysis.width, analysis.height, tileSize, tileOverlap)
                val progressSamples = FloatArray(max(1, totalTiles)) { 0f }
                val startElapsed = SystemClock.elapsedRealtime()
                var lastEmitElapsed = startElapsed
                var lastEmitProgress = 0f
                var latestResult: EnhancementResult? = null
                var pipelineSnapshot: EnhanceEngine.Pipeline? = null
                val predictedProfile = EnhanceEngine.ProfileCalculator.calculate(metrics, normalized)
                if (totalTiles > 0) {
                    _enhancementState.update { state ->
                        state.copy(progressByTile = (0 until totalTiles).associateWith { 0f })
                    }
                }
                val delegatePlan = selectEnhancementDelegate(metrics, normalized)
                logEnhancement(
                    action = "enhance_start",
                    photo = photo,
                    "strength" to "%.2f".format(normalized),
                    "delegate_plan" to delegatePlan.delegateType.name.lowercase(),
                    "engine_delegate_plan" to delegatePlan.engineDelegate.name.lowercase(),
                    "tiles_total" to totalTiles,
                    "tile_size" to tileSize,
                    "tile_overlap" to tileOverlap,
                )
                suspend fun emitEnhancementMetrics(force: Boolean = false) {
                    val now = SystemClock.elapsedRealtime()
                    val progress = if (totalTiles <= 0) {
                        if (latestResult != null) 1f else 0f
                    } else {
                        var sum = 0f
                        val limit = min(totalTiles, progressSamples.size)
                        for (i in 0 until limit) {
                            sum += progressSamples[i].coerceIn(0f, 1f)
                        }
                        if (limit == 0) 0f else (sum / limit)
                    }.coerceIn(0f, 1f)
                    val tilesCompleted = if (totalTiles <= 0) {
                        if (latestResult != null) totalTiles else 0
                    } else {
                        progressSamples.count { it >= 0.999f }
                    }
                    if (!force) {
                        val elapsedSinceLast = now - lastEmitElapsed
                        val progressDelta = progress - lastEmitProgress
                        if (elapsedSinceLast < ENHANCE_METRICS_INTERVAL_MS && progressDelta < ENHANCE_METRICS_PROGRESS_DELTA) {
                            return
                        }
                    }
                    lastEmitElapsed = now
                    lastEmitProgress = progress
                    val elapsed = now - startElapsed
                    val eta = if (progress >= ENHANCE_METRICS_PROGRESS_DELTA && progress < 0.999f) {
                        ((elapsed / progress) * (1f - progress)).roundToLong()
                    } else {
                        null
                    }
                    val (ramMb, vramMb) = collectMemoryTelemetry()
                    val queueStats = uploadQueueRepository.getQueueStats()
                    val queueLength = queueStats.queued + queueStats.processing
                    val currentResult = latestResult
                    val pipelineSource = currentResult?.pipeline
                        ?: pipelineSnapshot
                        ?: EnhanceEngine.Pipeline(
                            tileSize = tileSize,
                            overlap = tileOverlap,
                            tileCount = totalTiles,
                        )
                    val tileCountSource = if (pipelineSource.tileCount > 0) {
                        pipelineSource.tileCount
                    } else {
                        totalTiles
                    }
                    val overlapActual = when {
                        pipelineSource.tileSizeActual > 0 -> min(
                            pipelineSource.overlapActual,
                            pipelineSource.tileSizeActual / 2,
                        )
                        pipelineSource.overlapActual > 0 -> pipelineSource.overlapActual
                        else -> min(tileOverlap, tileSize / 2)
                    }
                    val mixingWindowActual = when {
                        pipelineSource.mixingWindowActual > 0 -> pipelineSource.mixingWindowActual
                        overlapActual > 0 -> overlapActual * 2
                        else -> pipelineSource.mixingWindowActual
                    }
                    val mixingWindow = if (pipelineSource.mixingWindow > 0) {
                        pipelineSource.mixingWindow
                    } else {
                        mixingWindowActual
                    }
                    val pipeline = pipelineSource.copy(
                        tileCount = tileCountSource,
                        tilesCompleted = tilesCompleted.coerceAtMost(
                            max(tileCountSource, totalTiles)
                        ),
                        tileProgress = progress,
                        overlapActual = overlapActual,
                        mixingWindow = mixingWindow,
                        mixingWindowActual = mixingWindowActual,
                    )
                    val delegate = currentResult?.delegate ?: delegatePlan.delegateType
                    val engineDelegate = currentResult?.engineDelegate ?: delegatePlan.engineDelegate
                    val models = currentResult?.models
                    val profile = currentResult?.profile ?: predictedProfile
                    val metricsForLog = currentResult?.metrics ?: metrics
                    val timingsBase = currentResult?.timings ?: EnhanceEngine.Timings()
                    val timings = timingsBase.copy(
                        elapsed = elapsed,
                        eta = eta,
                    )
                    logEnhancementMetrics(
                        photo = photo,
                        delegate = delegate,
                        engineDelegate = engineDelegate,
                        pipeline = pipeline,
                        timings = timings,
                        metrics = metricsForLog,
                        profile = profile,
                        models = models,
                        progress = progress,
                        ramMb = ramMb,
                        vramMb = vramMb,
                        queueLength = queueLength,
                    )
                }
                viewModelScope.launch { emitEnhancementMetrics(force = true) }
                val progressCallback: (EnhanceEngine.TileProgress) -> Unit = { tileProgress ->
                    val index = tileProgress.index
                    val progress = tileProgress.progress
                    viewModelScope.launch {
                        _enhancementState.update { state ->
                            if (index < 0) {
                                return@update state
                            }
                            val updated = state.progressByTile.toMutableMap()
                            if (index < totalTiles) {
                                updated[index] = progress
                            }
                            state.copy(progressByTile = updated)
                        }
                        if (index in progressSamples.indices) {
                            progressSamples[index] = progress.coerceIn(0f, 1f)
                        }
                        tileProgress.pipeline?.let { pipelineSnapshot = it }
                        emitEnhancementMetrics()
                    }
                }
                fun updateNativeProgress(progress: Float) {
                    val clamped = progress.coerceIn(0f, 1f)
                    viewModelScope.launch {
                        _enhancementState.update { state ->
                            val updated = if (state.progressByTile.isEmpty()) {
                                mutableMapOf<Int, Float>().apply { put(0, clamped) }
                            } else {
                                state.progressByTile.toMutableMap().apply { put(0, clamped) }
                            }
                            state.copy(progressByTile = updated)
                        }
                        if (progressSamples.isNotEmpty()) {
                            progressSamples[0] = clamped
                        }
                        emitEnhancementMetrics()
                    }
                }

                var fallbackReason: String? = null

                suspend fun tryNativeEnhancement(): EnhancementResult? {
                    if (nativeEnhanceAdapter == null) {
                        fallbackReason = "adapter_unavailable"
                        return null
                    }
                    val previewOk = nativeEnhanceAdapter.computePreview(
                        sourceFile = workspace.source,
                        strength = normalized,
                    ) { value -> updateNativeProgress(value) }
                    if (!previewOk) {
                        fallbackReason = "preview_failed"
                        return null
                    }
                    val info = nativeEnhanceAdapter.computeFull(
                        sourceFile = workspace.source,
                        strength = normalized,
                        outputFile = workspace.output,
                        exif = workspace.exif,
                    ) { value -> updateNativeProgress(value) } ?: run {
                        if (fallbackReason == null) {
                            fallbackReason = "full_failed"
                        }
                        return null
                    }
                    if (info.cancelled == true) {
                        fallbackReason = "cancelled"
                        return null
                    }
                    val metricsResult = info.metrics
                    val enhancementMetrics = EnhanceEngine.Metrics(
                        lMean = metricsResult.lMean.toDouble(),
                        pDark = metricsResult.pDark.toDouble(),
                        bSharpness = metricsResult.bSharpness.toDouble(),
                        nNoise = metricsResult.nNoise.toDouble(),
                    )
                    val previewMs = info.previewTimingMs ?: 0L
                    val fullMs = info.fullTimingMs ?: 0L
                    val totalTiming = previewMs + fullMs
                    val actualTileSize = if (analysis.width > 0) analysis.width else tileSize
                    val nativeTileCount = max(1, totalTiles)
                    val pipeline = EnhanceEngine.Pipeline(
                        stages = listOf(
                            "native_preview",
                            if (info.usedVulkan == true) "native_full_vulkan" else "native_full",
                        ),
                        tileSize = tileSize,
                        overlap = tileOverlap,
                        tileSizeActual = actualTileSize,
                        overlapActual = tileOverlap,
                        mixingWindow = 0,
                        mixingWindowActual = 0,
                        tileCount = nativeTileCount,
                        tilesCompleted = nativeTileCount,
                        tileProgress = 1f,
                        tileUsed = false,
                        zeroDceIterations = 0,
                        zeroDceApplied = true,
                        zeroDceDelegateFallback = false,
                        restormerMix = predictedProfile.restormerMix,
                        restormerApplied = true,
                        restormerDelegateFallback = false,
                        hasSeamFix = false,
                    )
                    val timings = EnhanceEngine.Timings(
                        decode = previewMs,
                        metrics = 0,
                        zeroDce = 0,
                        restormer = 0,
                        blend = 0,
                        sharpen = 0,
                        vibrance = 0,
                        encode = fullMs,
                        exif = 0,
                        total = totalTiming,
                        elapsed = totalTiming,
                        eta = 0L,
                    )
                    val result = EnhancementResult(
                        sourceFile = workspace.source,
                        file = workspace.output,
                        uri = workspace.output.toUri(),
                        strength = normalized,
                        metrics = enhancementMetrics,
                        profile = predictedProfile,
                        delegate = EnhancementDelegateType.PRIMARY,
                        engineDelegate = delegatePlan.engineDelegate,
                        pipeline = pipeline,
                        timings = timings,
                        models = nativeEnhanceAdapter?.modelsTelemetry(),
                        uploadInfo = info,
                    )
                    pipelineSnapshot = pipeline
                    return result
                }

                val result = try {
                    if (delegatePlan.delegateType == EnhancementDelegateType.PRIMARY) {
                        val nativeResult = tryNativeEnhancement()
                        if (nativeResult != null) {
                            nativeResult
                        } else {
                            val reason = fallbackReason ?: "native_failed"
                            logEnhancement(
                                action = "delegate_fallback",
                                photo = photo,
                                "reason" to reason,
                                "delegate" to delegatePlan.delegateType.name.lowercase(),
                                "engine_delegate" to delegatePlan.engineDelegate.name.lowercase(),
                            )
                            runFallbackEnhancement(workspace, metrics, normalized)
                        }
                    } else {
                        fallbackReason = "delegate_unavailable"
                        logEnhancement(
                            action = "delegate_fallback",
                            photo = photo,
                            "reason" to fallbackReason,
                            "delegate" to delegatePlan.delegateType.name.lowercase(),
                            "engine_delegate" to delegatePlan.engineDelegate.name.lowercase(),
                        )
                        runFallbackEnhancement(workspace, metrics, normalized)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Timber.tag(UI_TAG).e(error, "Enhancement failed for %s", photo.uri)
                    fallbackReason = error.message ?: error::class.java.simpleName
                    logEnhancement(
                        action = "delegate_fallback",
                        photo = photo,
                        "reason" to (error.message ?: error::class.java.simpleName),
                        "delegate" to delegatePlan.delegateType.name.lowercase(),
                        "engine_delegate" to delegatePlan.engineDelegate.name.lowercase(),
                    )
                    runFallbackEnhancement(workspace, metrics, normalized)
                }
                producedResult = result
                latestResult = result
                val matchesCurrentPhoto = currentPhoto.value?.id == photo.id
                _enhancementState.update { state ->
                    state.copy(
                        inProgress = false,
                        isResultReady = true,
                        progressByTile = emptyMap(),
                        result = result,
                        resultUri = result.uri,
                        resultPhotoId = photo.id,
                        isResultForCurrentPhoto = matchesCurrentPhoto,
                    )
                }
                logEnhancementDecision(photo, delegatePlan, analysis, result)
                emitEnhancementMetrics(force = true)
                logEnhancementResult(photo, result)
                if (result.delegate == EnhancementDelegateType.FALLBACK) {
                    logEnhancement(
                        action = "enhance_fallback_result",
                        photo = photo,
                        "reason" to (fallbackReason ?: "unknown"),
                        "delegate" to result.delegate.name.lowercase(),
                        "engine_delegate" to (result.engineDelegate?.name?.lowercase() ?: "none"),
                        "pipeline" to result.pipeline.stages.joinToString(separator = "+"),
                        "tile_count" to result.pipeline.tileCount,
                        "tile_used" to result.pipeline.tileUsed,
                        "seam_max_delta" to result.pipeline.seamMaxDelta.format3(),
                        "seam_mean_delta" to result.pipeline.seamMeanDelta.format3(),
                        "seam_area" to result.pipeline.seamArea,
                        "seam_zero_area" to result.pipeline.seamZeroArea,
                        "seam_min_weight" to result.pipeline.seamMinWeight.format3(),
                        "seam_max_weight" to result.pipeline.seamMaxWeight.format3(),
                    )
                }
            } finally {
                if (producedResult == null) {
                    workspace.cleanup()
                }
            }
        }
        enhancementJob = job
        job.invokeOnCompletion { throwable ->
            if (throwable is CancellationException) {
                return@invokeOnCompletion
            }
            if (throwable != null) {
                Timber.tag(UI_TAG).e(throwable, "Enhancement failed for %s", photo.uri)
            }
            if (enhancementJob === job) {
                enhancementJob = null
            }
        }
    }

    private fun cancelEnhancementJob(resetToReady: Boolean = false) {
        val job = enhancementJob
        if (job != null) {
            job.cancel()
            enhancementJob = null
        }
        if (resetToReady) {
            disposeEnhancementResult(_enhancementState.value.result)
        }
        _enhancementState.update { state ->
            state.copy(
                inProgress = false,
                isResultReady = if (resetToReady) true else false,
                progressByTile = emptyMap(),
                result = if (resetToReady) null else state.result,
                resultUri = null,
                resultPhotoId = null,
                isResultForCurrentPhoto = false,
            )
        }
    }

    private fun disposeEnhancementResult(
        result: EnhancementResult?,
        disposition: EnhancementResultDisposition = EnhancementResultDisposition.DISCARD,
    ) {
        val target = result ?: return
        when (disposition) {
            EnhancementResultDisposition.DISCARD -> {
                runCatching { if (target.file.exists()) target.file.delete() }
                runCatching { if (target.sourceFile.exists()) target.sourceFile.delete() }
            }
            EnhancementResultDisposition.ENQUEUED -> {
                runCatching { if (target.sourceFile.exists()) target.sourceFile.delete() }
            }
            EnhancementResultDisposition.UPLOADED -> {
                runCatching { if (target.file.exists()) target.file.delete() }
                runCatching { if (target.sourceFile.exists()) target.sourceFile.delete() }
            }
        }
    }

    private fun scheduleUploadCleanup(data: PendingUploadCleanup) {
        pendingCleanupJobs.remove(data.idempotencyKey)?.cancel()
        val job = viewModelScope.launch {
            try {
                val finalState = awaitUploadCompletion(data)
                if (finalState == UploadItemState.FAILED) {
                    logEnhancement(
                        action = "cleanup_error",
                        photo = data.photo,
                        "reason" to "upload_failed",
                        "upload_state" to finalState.name.lowercase(),
                        "cleanup_result" to data.useEnhancedResult,
                        "cleanup_strategy" to "post_upload",
                    )
                    return@launch
                }
                val outcome = performUploadCleanup(data)
                val action = if (outcome.isSuccess) "cleanup_ok" else "cleanup_error"
                val cleanupDetails = mutableListOf(
                    "source_deleted" to outcome.source.success,
                    "source_attempts" to outcome.source.attempts,
                    "result_deleted" to outcome.result.success,
                    "result_attempts" to outcome.result.attempts,
                    "upload_state" to (finalState?.name?.lowercase() ?: "unknown"),
                    "cleanup_result" to data.useEnhancedResult,
                    "cleanup_strategy" to "post_upload",
                )
                outcome.source.lastError?.let { cleanupDetails += "source_error" to it }
                outcome.result.lastError?.let { cleanupDetails += "result_error" to it }
                logEnhancement(
                    action = action,
                    photo = data.photo,
                    *cleanupDetails.toTypedArray(),
                )
                val enhanceCleanupDetails = mutableListOf(
                    "source_deleted" to outcome.source.success,
                    "result_deleted" to outcome.result.success,
                    "cleanup_attempts_source" to outcome.source.attempts,
                    "cleanup_attempts_result" to outcome.result.attempts,
                    "result_present" to (data.enhancementResult != null),
                    "upload_state" to (finalState?.name?.lowercase() ?: "unknown"),
                    "cleanup_strategy" to "post_upload",
                )
                logEnhancement(
                    action = "enhance_cleanup",
                    photo = data.photo,
                    *enhanceCleanupDetails.toTypedArray(),
                )
            } catch (error: Exception) {
                logEnhancement(
                    action = "cleanup_error",
                    photo = data.photo,
                    "reason" to (error.message ?: error::class.simpleName?.lowercase()),
                    "cleanup_result" to data.useEnhancedResult,
                    "cleanup_strategy" to "post_upload",
                )
            } finally {
                pendingCleanupJobs.remove(data.idempotencyKey)
            }
        }
        pendingCleanupJobs[data.idempotencyKey] = job
    }

    private suspend fun awaitUploadCompletion(data: PendingUploadCleanup): UploadItemState? {
        uploadQueueRepository.observeQueuedOrProcessing(data.enqueueUri)
            .filter { enqueued -> !enqueued }
            .first()
        return uploadQueueRepository.observeQueue()
            .map { entries ->
                entries.firstOrNull { entry ->
                    entry.entity.idempotencyKey == data.idempotencyKey ||
                        entry.entity.photoId == data.photo.id
                }?.state
            }
            .firstOrNull { state ->
                state == null || state == UploadItemState.SUCCEEDED || state == UploadItemState.FAILED
            }
    }

    private suspend fun performUploadCleanup(data: PendingUploadCleanup): CleanupOutcome {
        val sourceAttempt = deletePhotoDocumentWithRetry(data.documentInfo.uri)
        val resultAttempt = deleteEnhancementFileWithRetry(data.enhancementResult)
        data.enhancementResult?.let { disposeEnhancementResult(it, EnhancementResultDisposition.UPLOADED) }
        return CleanupOutcome(source = sourceAttempt, result = resultAttempt)
    }

    private suspend fun deletePhotoDocumentWithRetry(uri: Uri): CleanupAttempt =
        retryCleanup { deletePhotoDocumentOnce(uri) }

    private suspend fun deleteEnhancementFileWithRetry(result: EnhancementResult?): CleanupAttempt {
        val target = result ?: return CleanupAttempt(success = true, attempts = 0, lastError = null)
        return retryCleanup {
            withContext(Dispatchers.IO) {
                if (!target.file.exists()) {
                    return@withContext true
                }
                target.file.delete() || !target.file.exists()
            }
        }
    }

    private suspend fun deletePhotoDocumentOnce(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        when {
            uri.scheme == ContentResolver.SCHEME_FILE -> {
                val path = uri.path ?: return@withContext true
                val file = File(path)
                if (!file.exists()) {
                    return@withContext true
                }
                file.delete() || !file.exists()
            }
            uri.authority == MediaStore.AUTHORITY -> deleteMediaStoreDocument(uri)
            else -> {
                val document = DocumentFile.fromSingleUri(context, uri) ?: return@withContext true
                if (!document.exists()) {
                    return@withContext true
                }
                document.delete() || !document.exists()
            }
        }
    }

    private fun deleteMediaStoreDocument(uri: Uri): Boolean {
        val resolver = context.contentResolver
        val rowsDeleted = runCatching { resolver.delete(uri, null, null) }
            .getOrElse { error ->
                if (error is RecoverableSecurityException || error is SecurityException) {
                    return false
                }
                throw error
            }
        if (rowsDeleted > 0) {
            return true
        }
        return !mediaStoreEntryExists(resolver, uri)
    }

    private fun mediaStoreEntryExists(resolver: ContentResolver, uri: Uri): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use(Cursor::moveToFirst) ?: false
        }.getOrElse { true }
    }

    private suspend fun retryCleanup(
        attempts: Int = 3,
        block: suspend () -> Boolean,
    ): CleanupAttempt {
        var lastError: String? = null
        repeat(attempts) { index ->
            val success = runCatching { block() }.getOrElse { error ->
                lastError = error.message ?: error::class.simpleName
                false
            }
            if (success) {
                return CleanupAttempt(success = true, attempts = index + 1, lastError = lastError)
            }
            if (index < attempts - 1) {
                delay(250L)
            }
        }
        return CleanupAttempt(success = false, attempts = attempts, lastError = lastError)
    }

    private suspend fun createEnhancementWorkspace(photo: PhotoItem): EnhancementWorkspace =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "enhance")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Unable to create enhancement cache directory at ${directory.absolutePath}")
            }
            val source = File.createTempFile("src_", ".jpg", directory)
            val resolver = context.contentResolver
            val normalizedUri = resolver.requireOriginalIfNeeded(photo.uri)
            resolver.logUriReadDebug("ViewerViewModel.enhance", photo.uri, normalizedUri)
            resolver.openInputStream(normalizedUri)?.use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Unable to open input stream for $normalizedUri")
            val exif = runCatching { ExifInterface(source) }.getOrNull()
            val output = File(directory, "${source.nameWithoutExtension}_out.jpg")
            EnhancementWorkspace(source = source, output = output, exif = exif)
        }

    private suspend fun analyzeWorkspace(workspace: EnhancementWorkspace): WorkspaceAnalysis =
        withContext(Dispatchers.IO) {
            val buffer = EnhanceEngine.BitmapImageDecoder().decode(workspace.source)
            val metrics = EnhanceEngine.MetricsCalculator.calculate(buffer)
            WorkspaceAnalysis(metrics = metrics, width = buffer.width, height = buffer.height)
        }

    private fun selectEnhancementDelegate(
        metrics: EnhanceEngine.Metrics,
        strength: Float,
    ): EnhancementDelegatePlan {
        if (nativeEnhanceAdapter?.isReady() == true) {
            return EnhancementDelegatePlan(
                delegateType = EnhancementDelegateType.PRIMARY,
                engineDelegate = selectEngineDelegate(),
            )
        }
        return EnhancementDelegatePlan(
            delegateType = EnhancementDelegateType.FALLBACK,
            engineDelegate = EnhanceEngine.Delegate.CPU,
        )
    }

    private fun selectEngineDelegate(): EnhanceEngine.Delegate {
        val sdk = buildVersionOverride ?: Build.VERSION.SDK_INT
        return if (sdk >= Build.VERSION_CODES.O) {
            EnhanceEngine.Delegate.GPU
        } else {
            EnhanceEngine.Delegate.CPU
        }
    }

    private suspend fun runFallbackEnhancement(
        workspace: EnhancementWorkspace,
        metrics: EnhanceEngine.Metrics,
        strength: Float,
    ): EnhancementResult = withContext(Dispatchers.IO) {
        if (workspace.output.exists()) {
            runCatching { workspace.output.delete() }
        }
        workspace.source.copyTo(workspace.output, overwrite = true)
        EnhancementResult(
            sourceFile = workspace.source,
            file = workspace.output,
            uri = workspace.output.toUri(),
            strength = strength,
            metrics = metrics,
            profile = FALLBACK_PROFILE,
            delegate = EnhancementDelegateType.FALLBACK,
            engineDelegate = null,
            pipeline = EnhanceEngine.Pipeline(
                stages = listOf("copy"),
                tileSize = 0,
                overlap = 0,
                tileCount = 0,
                tilesCompleted = 0,
                tileProgress = 0f,
                zeroDceIterations = 0,
                zeroDceApplied = false,
                restormerMix = 0f,
                restormerApplied = false,
                hasSeamFix = false,
            ),
            timings = EnhanceEngine.Timings(),
            models = null,
        )
    }

    private fun strengthsEqual(first: Float, second: Float): Boolean {
        return abs(first - second) < 0.0001f
    }

    private fun computeTileCount(width: Int, height: Int, tileSize: Int, overlap: Int): Int {
        if (tileSize <= 0 || width <= 0 || height <= 0) {
            return 0
        }
        val safeTile = tileSize.coerceAtLeast(1)
        val safeOverlap = overlap.coerceAtLeast(0)
        val step = max(1, safeTile - min(safeOverlap, safeTile / 2))
        val tilesX = ceil(width / step.toDouble()).toInt()
        val tilesY = ceil(height / step.toDouble()).toInt()
        return (tilesX * tilesY).coerceAtLeast(0)
    }

    private fun EnhancementWorkspace.cleanup() {
        runCatching { if (source.exists()) source.delete() }
        runCatching { if (output.exists()) output.delete() }
    }

    private fun EnhancementResult.zeroDceBackend(): String = models?.zeroDce?.backend?.name?.lowercase() ?: "none"

    private fun EnhancementResult.restormerBackend(): String = models?.restormer?.backend?.name?.lowercase() ?: "none"

    private fun EnhancementResult.zeroDceSha(): String = models?.zeroDce?.checksum ?: "none"

    private fun zeroDceBackend(models: EnhanceEngine.ModelsTelemetry?): String =
        models?.zeroDce?.backend?.name?.lowercase() ?: "none"

    private fun restormerBackend(models: EnhanceEngine.ModelsTelemetry?): String =
        models?.restormer?.backend?.name?.lowercase() ?: "none"

    private fun zeroDceSha(models: EnhanceEngine.ModelsTelemetry?): String =
        models?.zeroDce?.checksum ?: "none"

    private fun restormerSha(models: EnhanceEngine.ModelsTelemetry?): String =
        models?.restormer?.checksum ?: "none"

    private fun zeroDceShaOk(models: EnhanceEngine.ModelsTelemetry?): Boolean? =
        models?.zeroDce?.checksumOk

    private fun restormerShaOk(models: EnhanceEngine.ModelsTelemetry?): Boolean? =
        models?.restormer?.checksumOk

    private fun collectMemoryTelemetry(): Pair<Double, Double?> {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()).toDouble() / BYTES_IN_MB
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val graphicsKb = memoryInfo.memoryStats["summary.graphics"]?.toLongOrNull()
        val graphicsMb = graphicsKb?.div(1024.0)
        return usedMb to graphicsMb
    }

    private fun Double.format3(): String = String.format(Locale.US, "%.3f", this)
    private fun Float.format3(): String = String.format(Locale.US, "%.3f", this)
    private fun Float.format2(): String = String.format(Locale.US, "%.2f", this)
    private fun Double.format1(): String = String.format(Locale.US, "%.1f", this)

    private fun logEnhancement(action: String, photo: PhotoItem, vararg details: Pair<String, Any?>) {
        val message = UploadLog.message(
            category = ENHANCE_CATEGORY,
            action = action,
            photoId = photo.id,
            uri = photo.uri,
            details = details,
        )
        Timber.tag(ENHANCE_TAG).i(message)
        val payload = LinkedHashMap<String, Any?>(details.size + 4)
        payload["photo_id"] = photo.id
        payload["uri"] = photo.uri.toString()
        details.forEach { (key, value) ->
            if (key.isNotBlank()) {
                payload[key] = value
            }
        }
        EnhanceLogging.logEvent(action, payload)
    }

    private fun logEnhancementDecision(
        photo: PhotoItem,
        plan: EnhancementDelegatePlan,
        analysis: WorkspaceAnalysis,
        result: EnhancementResult,
    ) {
        val pipelineStages = result.pipeline.stages.joinToString(separator = "+").ifEmpty { "none" }
        val engineDelegateActual = result.engineDelegate?.name?.lowercase() ?: "none"
        logEnhancement(
            action = "enhance_decision",
            photo = photo,
            "strength" to result.strength.format2(),
            "pipeline" to pipelineStages,
            "delegate_plan" to plan.delegateType.name.lowercase(),
            "delegate_actual" to result.delegate.name.lowercase(),
            "engine_delegate_plan" to plan.engineDelegate.name.lowercase(),
            "engine_delegate_actual" to engineDelegateActual,
            "zero_dce_backend" to zeroDceBackend(result.models),
            "zero_dce_sha256" to zeroDceSha(result.models),
            "sha256_ok_zero_dce" to zeroDceShaOk(result.models),
            "restormer_backend" to restormerBackend(result.models),
            "restormer_sha256" to restormerSha(result.models),
            "sha256_ok_restormer" to restormerShaOk(result.models),
            "tile_used" to result.pipeline.tileUsed,
            "k_dce" to result.profile.kDce.format3(),
            "alpha_detail" to result.profile.alphaDetail.format3(),
            "restormer_mix" to result.profile.restormerMix.format3(),
            "sharpen_amount" to result.profile.sharpenAmount.format3(),
            "sharpen_radius" to result.profile.sharpenRadius.format3(),
            "sharpen_threshold" to result.profile.sharpenThreshold.format3(),
            "vibrance_gain" to result.profile.vibranceGain.format3(),
            "saturation_gain" to result.profile.saturationGain.format3(),
            "tile_size" to result.pipeline.tileSize,
            "tile_overlap" to result.pipeline.overlap,
            "tile_size_actual" to result.pipeline.tileSizeActual,
            "tile_overlap_actual" to result.pipeline.overlapActual,
            "mixing_window" to result.pipeline.mixingWindow,
            "mixing_window_actual" to result.pipeline.mixingWindowActual,
            "tile_count" to result.pipeline.tileCount,
            "tiles_completed" to result.pipeline.tilesCompleted,
            "tile_progress" to result.pipeline.tileProgress.format3(),
            "seam_max_delta" to result.pipeline.seamMaxDelta.format3(),
            "seam_mean_delta" to result.pipeline.seamMeanDelta.format3(),
            "seam_area" to result.pipeline.seamArea,
            "seam_zero_area" to result.pipeline.seamZeroArea,
            "seam_min_weight" to result.pipeline.seamMinWeight.format3(),
            "seam_max_weight" to result.pipeline.seamMaxWeight.format3(),
            "zero_dce_iterations" to result.pipeline.zeroDceIterations,
            "zero_dce_applied" to result.pipeline.zeroDceApplied,
            "restormer_applied" to result.pipeline.restormerApplied,
            "zero_dce_delegate_fallback" to result.pipeline.zeroDceDelegateFallback,
            "restormer_delegate_fallback" to result.pipeline.restormerDelegateFallback,
            "has_seam_fix" to result.pipeline.hasSeamFix,
            "metrics_l_mean" to analysis.metrics.lMean.format3(),
            "metrics_p_dark" to analysis.metrics.pDark.format3(),
            "metrics_b_sharpness" to analysis.metrics.bSharpness.format3(),
            "metrics_n_noise" to analysis.metrics.nNoise.format3(),
        )
    }

    private fun logEnhancementMetrics(
        photo: PhotoItem,
        delegate: EnhancementDelegateType,
        engineDelegate: EnhanceEngine.Delegate?,
        pipeline: EnhanceEngine.Pipeline,
        timings: EnhanceEngine.Timings,
        metrics: EnhanceEngine.Metrics,
        profile: EnhanceEngine.Profile,
        models: EnhanceEngine.ModelsTelemetry?,
        progress: Float,
        ramMb: Double,
        vramMb: Double?,
        queueLength: Int,
    ) {
        val pipelineStages = pipeline.stages.joinToString(separator = "+").ifEmpty { "none" }
        val engineDelegateActual = engineDelegate?.name?.lowercase() ?: "none"
        logEnhancement(
            action = "enhance_metrics",
            photo = photo,
            "pipeline" to pipelineStages,
            "delegate" to delegate.name.lowercase(),
            "delegate_actual" to delegate.name.lowercase(),
            "engine_delegate" to engineDelegateActual,
            "zero_dce_backend" to zeroDceBackend(models),
            "zero_dce_sha256" to zeroDceSha(models),
            "sha256_ok_zero_dce" to zeroDceShaOk(models),
            "restormer_backend" to restormerBackend(models),
            "restormer_sha256" to restormerSha(models),
            "sha256_ok_restormer" to restormerShaOk(models),
            "tile_used" to pipeline.tileUsed,
            "seam_max_delta" to pipeline.seamMaxDelta.format3(),
            "seam_mean_delta" to pipeline.seamMeanDelta.format3(),
            "seam_area" to pipeline.seamArea,
            "seam_zero_area" to pipeline.seamZeroArea,
            "seam_min_weight" to pipeline.seamMinWeight.format3(),
            "seam_max_weight" to pipeline.seamMaxWeight.format3(),
            "mixing_window" to pipeline.mixingWindow,
            "tile_size" to pipeline.tileSize,
            "tile_overlap" to pipeline.overlap,
            "tile_size_actual" to pipeline.tileSizeActual,
            "tile_overlap_actual" to pipeline.overlapActual,
            "tile_count" to pipeline.tileCount,
            "tiles_completed" to pipeline.tilesCompleted,
            "tile_progress" to pipeline.tileProgress.format3(),
            "mixing_window_actual" to pipeline.mixingWindowActual,
            "zero_dce_iterations" to pipeline.zeroDceIterations,
            "zero_dce_applied" to pipeline.zeroDceApplied,
            "restormer_applied" to pipeline.restormerApplied,
            "zero_dce_delegate_fallback" to pipeline.zeroDceDelegateFallback,
            "restormer_delegate_fallback" to pipeline.restormerDelegateFallback,
            "has_seam_fix" to pipeline.hasSeamFix,
            "l_mean" to metrics.lMean.format3(),
            "p_dark" to metrics.pDark.format3(),
            "b_sharpness" to metrics.bSharpness.format3(),
            "n_noise" to metrics.nNoise.format3(),
            "k_dce" to profile.kDce.format3(),
            "alpha_detail" to profile.alphaDetail.format3(),
            "restormer_mix" to profile.restormerMix.format3(),
            "sharpen_amount" to profile.sharpenAmount.format3(),
            "vibrance_gain" to profile.vibranceGain.format3(),
            "saturation_gain" to profile.saturationGain.format3(),
            "progress" to progress.format3(),
            "elapsed_ms" to timings.elapsed,
            "eta_ms" to (timings.eta ?: -1L),
            "ram_mb" to ramMb.format1(),
            "vram_mb" to vramMb?.format1(),
            "queue_len" to queueLength,
        )
    }

    private fun logEnhancementResult(
        photo: PhotoItem,
        result: EnhancementResult,
    ) {
        val pipelineStages = result.pipeline.stages.joinToString(separator = "+").ifEmpty { "none" }
        val engineDelegateActual = result.engineDelegate?.name?.lowercase() ?: "none"
        val timings = result.timings
        logEnhancement(
            action = "enhance_result",
            photo = photo,
            "pipeline" to pipelineStages,
            "delegate" to result.delegate.name.lowercase(),
            "delegate_actual" to result.delegate.name.lowercase(),
            "engine_delegate" to engineDelegateActual,
            "strength" to result.strength.format2(),
            "file_size" to result.file.length(),
            "zero_dce_backend" to zeroDceBackend(result.models),
            "zero_dce_sha256" to zeroDceSha(result.models),
            "sha256_ok_zero_dce" to zeroDceShaOk(result.models),
            "restormer_backend" to restormerBackend(result.models),
            "restormer_sha256" to restormerSha(result.models),
            "sha256_ok_restormer" to restormerShaOk(result.models),
            "tile_used" to result.pipeline.tileUsed,
            "seam_max_delta" to result.pipeline.seamMaxDelta.format3(),
            "seam_mean_delta" to result.pipeline.seamMeanDelta.format3(),
            "seam_area" to result.pipeline.seamArea,
            "seam_zero_area" to result.pipeline.seamZeroArea,
            "seam_min_weight" to result.pipeline.seamMinWeight.format3(),
            "seam_max_weight" to result.pipeline.seamMaxWeight.format3(),
            "duration_total_ms" to timings.total,
            "duration_decode_ms" to timings.decode,
            "duration_metrics_ms" to timings.metrics,
            "duration_zero_dce_ms" to timings.zeroDce,
            "duration_restormer_ms" to timings.restormer,
            "duration_blend_ms" to timings.blend,
            "duration_sharpen_ms" to timings.sharpen,
            "duration_vibrance_ms" to timings.vibrance,
            "duration_encode_ms" to timings.encode,
            "duration_exif_ms" to timings.exif,
            "tile_size" to result.pipeline.tileSize,
            "tile_overlap" to result.pipeline.overlap,
            "tile_size_actual" to result.pipeline.tileSizeActual,
            "tile_overlap_actual" to result.pipeline.overlapActual,
            "mixing_window" to result.pipeline.mixingWindow,
            "mixing_window_actual" to result.pipeline.mixingWindowActual,
            "tile_count" to result.pipeline.tileCount,
            "zero_dce_iterations" to result.pipeline.zeroDceIterations,
            "zero_dce_applied" to result.pipeline.zeroDceApplied,
            "restormer_applied" to result.pipeline.restormerApplied,
            "zero_dce_delegate_fallback" to result.pipeline.zeroDceDelegateFallback,
            "restormer_delegate_fallback" to result.pipeline.restormerDelegateFallback,
            "has_seam_fix" to result.pipeline.hasSeamFix,
        )
    }

    private fun restoreUndoStack() {
        val saved = savedStateHandle.get<ArrayList<UndoEntryState>>(undoStackKey)
        if (saved.isNullOrEmpty()) {
            _undoCount.value = 0
            updateCanUndo()
            return
        }
        saved.mapNotNull { state ->
            runCatching { state.toUserAction() }.getOrNull()
        }.forEach { action ->
            undoStack.addLast(action)
        }
        _undoCount.value = undoStack.size
        updateCanUndo()
    }

    private suspend fun buildIdempotencyKey(info: DocumentInfo): String = withContext(Dispatchers.IO) {
        val digest = Hashing.sha256(context.contentResolver, info.uri)
        idempotencyKeyFromContentSha256(digest)
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
        data class QueuedDeletion(
            val mediaId: Long,
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

    private data class PendingUploadCleanup(
        val photo: PhotoItem,
        val documentInfo: DocumentInfo,
        val enhancementResult: EnhancementResult?,
        val useEnhancedResult: Boolean,
        val idempotencyKey: String,
        val enqueueUri: Uri,
    )

    private data class CleanupAttempt(
        val success: Boolean,
        val attempts: Int,
        val lastError: String?,
    )

    private data class CleanupOutcome(
        val source: CleanupAttempt,
        val result: CleanupAttempt,
    ) {
        val isSuccess: Boolean get() = source.success && result.success
    }

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
        val mediaId: Long? = null,
        val takenAt: Long?
    ) : Serializable

    private enum class UserActionType : Serializable {
        Skip,
        MovedToProcessing,
        EnqueuedUpload,
        QueuedDeletion,
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
        is UserAction.QueuedDeletion -> UndoEntryState(
            type = UserActionType.QueuedDeletion,
            fromIndex = fromIndex,
            toIndex = toIndex,
            fromUri = uri.toString(),
            toUri = null,
            originalParent = null,
            displayName = null,
            mimeType = null,
            backupPath = null,
            mediaId = mediaId,
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
        UserActionType.QueuedDeletion -> UserAction.QueuedDeletion(
            mediaId = requireNotNull(mediaId),
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
        private const val QUEUE_DELETE_REASON = "user_delete"
        private const val DELETION_QUEUE_TAG = "DeletionQueue"
        private const val LOG_TAG = "ViewerViewModel"
        private const val UI_TAG = "UI"
        private const val PERMISSION_TAG = "Permissions"
        private const val MIN_ENHANCEMENT_STRENGTH = 0f
        private const val MAX_ENHANCEMENT_STRENGTH = 1f
        private const val ENHANCE_TAG = "Enhance"
        private const val ENHANCE_CATEGORY = "ENHANCE"
        private const val DEFAULT_ENHANCE_TILE_SIZE = 512
        private const val DEFAULT_ENHANCE_TILE_OVERLAP = 64
        private const val ENHANCE_METRICS_INTERVAL_MS = 1_000L
        private const val ENHANCE_METRICS_PROGRESS_DELTA = 0.01f
        private const val BYTES_IN_MB = 1024.0 * 1024.0
        private val FALLBACK_PROFILE = EnhanceEngine.Profile(
            isLowLight = false,
            kDce = 0f,
            restormerMix = 0f,
            alphaDetail = 0f,
            sharpenAmount = 0f,
            sharpenRadius = 1f,
            sharpenThreshold = 0.02f,
            vibranceGain = 0f,
            saturationGain = 1f,
        )

        internal var buildVersionOverride: Int? = null
        
        internal var mediaStoreDeleteRequestFactory: (ContentResolver, List<Uri>) -> PendingIntent = { resolver, uris ->
            MediaStore.createDeleteRequest(resolver, uris)
        }
    }

    data class EnhancementState(
        val strength: Float = 0f,
        val progressByTile: Map<Int, Float> = emptyMap(),
        val inProgress: Boolean = false,
        val isResultReady: Boolean = true,
        val result: EnhancementResult? = null,
        val resultUri: Uri? = null,
        val resultPhotoId: String? = null,
        val isResultForCurrentPhoto: Boolean = false,
    )

    enum class EnhancementResultDisposition { DISCARD, ENQUEUED, UPLOADED }

    data class EnhancementResult(
        val sourceFile: File,
        val file: File,
        val uri: Uri,
        val strength: Float,
        val metrics: EnhanceEngine.Metrics,
        val profile: EnhanceEngine.Profile,
        val delegate: EnhancementDelegateType,
        val engineDelegate: EnhanceEngine.Delegate?,
        val pipeline: EnhanceEngine.Pipeline,
        val timings: EnhanceEngine.Timings,
        val models: EnhanceEngine.ModelsTelemetry?,
        val uploadInfo: UploadEnhancementInfo? = null,
    )

    enum class EnhancementDelegateType { PRIMARY, FALLBACK }

    private data class EnhancementDelegatePlan(
        val delegateType: EnhancementDelegateType,
        val engineDelegate: EnhanceEngine.Delegate,
    )

    private data class EnhancementWorkspace(
        val source: File,
        val output: File,
        val exif: ExifInterface?,
    )

    private data class WorkspaceAnalysis(
        val metrics: EnhanceEngine.Metrics,
        val width: Int,
        val height: Int,
    )
}
