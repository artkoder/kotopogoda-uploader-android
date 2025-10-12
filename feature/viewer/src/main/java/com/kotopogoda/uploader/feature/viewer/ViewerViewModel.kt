package com.kotopogoda.uploader.feature.viewer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.feature.viewer.R
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.data.sa.SaFileRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import com.kotopogoda.uploader.core.settings.reviewProgressFolderId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Serializable
import java.security.MessageDigest
import java.util.ArrayList
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.text.Charsets

@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val folderRepository: FolderRepository,
    private val saFileRepository: SaFileRepository,
    private val uploadEnqueuer: UploadEnqueuer,
    private val reviewProgressStore: ReviewProgressStore,
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    val photos: StateFlow<List<PhotoItem>> = photoRepository.observePhotos()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

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

    private val undoStack = ArrayDeque<UserAction>()
    private val undoStackKey = "viewer_undo_stack"

    private val _undoCount = MutableStateFlow(0)
    val undoCount: StateFlow<Int> = _undoCount.asStateFlow()

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

    init {
        restoreUndoStack()
        savedStateHandle[currentIndexKey] = _currentIndex.value

        viewModelScope.launch {
            combine(currentIndex, photos) { index, photos -> index to photos.size }
                .collect { (index, count) ->
                    val maxIndex = (count - 1).coerceAtLeast(0)
                    val clamped = index.coerceIn(0, maxIndex)
                    if (clamped != index) {
                        setCurrentIndex(clamped)
                    }
                }
        }

        viewModelScope.launch {
            val folder = folderRepository.getFolder()
            if (folder != null) {
                val id = reviewProgressFolderId(folder.treeUri)
                folderId.value = id
                val stored = reviewProgressStore.loadPosition(id)
                val initialAnchor = stored?.anchorDate
                    ?: photos.value.getOrNull(_currentIndex.value)?.takenAt
                anchorDate.value = stored?.anchorDate ?: initialAnchor
                persistProgress(_currentIndex.value, anchorDate.value)
            }
        }

        viewModelScope.launch {
            combine(currentIndex, photos) { index, items ->
                index to items.getOrNull(index)?.takenAt
            }
                .debounce(300)
                .collect { (index, takenAt) ->
                    persistProgress(index, takenAt)
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
        _currentIndex.value = normalized
        savedStateHandle[currentIndexKey] = normalized
    }

    fun jumpToDate(target: Instant) {
        viewModelScope.launch {
            val index = photoRepository.findIndexAtOrAfter(target)
            setCurrentIndex(index)
        }
    }

    fun onSkip() {
        if (actionInProgress.value != null) {
            return
        }
        val snapshot = photos.value
        if (snapshot.isEmpty()) {
            return
        }
        val fromIndex = currentIndex.value
        val toIndex = (fromIndex + 1).coerceAtMost(snapshot.lastIndex)
        if (toIndex == fromIndex) {
            return
        }
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

    fun onMoveToProcessing() {
        if (_actionInProgress.value != null) {
            return
        }
        val snapshot = photos.value
        val photo = snapshot.getOrNull(currentIndex.value) ?: return
        viewModelScope.launch {
            _actionInProgress.value = ViewerActionInProgress.Processing
            try {
                val documentInfo = loadDocumentInfo(photo.uri)
                val fromIndex = currentIndex.value
                val toIndex = computeNextIndex(fromIndex, snapshot)
                val processingUri = saFileRepository.moveToProcessing(photo.uri)
                pushAction(
                    UserAction.MovedToProcessing(
                        fromUri = photo.uri,
                        toUri = processingUri,
                        originalParent = documentInfo.parentUri,
                        displayName = documentInfo.displayName,
                        fromIndex = fromIndex,
                        toIndex = toIndex
                    )
                )
                if (toIndex != fromIndex) {
                    setCurrentIndex(toIndex)
                }
                persistProgress(toIndex, photo.takenAt)
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_processing_success,
                        withUndo = true
                    )
                )
            } catch (error: Exception) {
                persistUndoStack()
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_processing_failed
                    )
                )
            } finally {
                _actionInProgress.value = null
            }
        }
    }

    fun onEnqueueUpload() {
        if (_actionInProgress.value != null) {
            return
        }
        val snapshot = photos.value
        val photo = snapshot.getOrNull(currentIndex.value) ?: return
        viewModelScope.launch {
            _actionInProgress.value = ViewerActionInProgress.Upload
            try {
                val documentInfo = loadDocumentInfo(photo.uri)
                val fromIndex = currentIndex.value
                val toIndex = computeNextIndex(fromIndex, snapshot)
                val idempotencyKey = buildIdempotencyKey(documentInfo)
                uploadEnqueuer.enqueue(
                    uri = photo.uri,
                    idempotencyKey = idempotencyKey,
                    displayName = documentInfo.displayName
                )
                pushAction(
                    UserAction.EnqueuedUpload(
                        uri = photo.uri,
                        fromIndex = fromIndex,
                        toIndex = toIndex
                    )
                )
                if (toIndex != fromIndex) {
                    setCurrentIndex(toIndex)
                }
                persistProgress(toIndex, photo.takenAt)
                _events.emit(
                    ViewerEvent.ShowSnackbar(
                        messageRes = R.string.viewer_snackbar_publish_success,
                        withUndo = true
                    )
                )
            } catch (error: Exception) {
                persistUndoStack()
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

    fun onUndo() {
        if (_actionInProgress.value != null) {
            return
        }
        val action = undoStack.removeLastOrNull() ?: return
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
        }
    }

    fun observeUploadEnqueued(uri: Uri) = uploadEnqueuer.isEnqueued(uri)

    private suspend fun loadDocumentInfo(uri: Uri): DocumentInfo = withContext(Dispatchers.IO) {
        val folder = folderRepository.getFolder()
            ?: throw IllegalStateException("Root folder is not selected")
        val treeUri = Uri.parse(folder.treeUri)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID
        )
        val resolver = context.contentResolver
        val cursor = resolver.query(uri, projection, null, null, null)
            ?: throw IllegalStateException("Unable to query document $uri")
        cursor.use { result ->
            if (!result.moveToFirst()) {
                throw IllegalStateException("Unable to read document info for $uri")
            }
            val displayNameIndex = result.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeIndex = result.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val lastModifiedIndex = result.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
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
            val documentId = result.getString(documentIdIndex)
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocumentId = when {
                documentId == null -> treeDocumentId
                documentId == treeDocumentId -> treeDocumentId
                else -> {
                    val slashIndex = documentId.lastIndexOf('/')
                    if (slashIndex > 0) {
                        documentId.substring(0, slashIndex)
                    } else {
                        treeDocumentId
                    }
                }
            }
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
            DocumentInfo(
                uri = uri,
                displayName = name,
                parentUri = parentUri,
                size = size,
                lastModified = lastModified
            )
        }
    }

    private suspend fun persistProgress(index: Int, anchor: Instant?) {
        val folderId = folderId.value ?: return
        val normalizedIndex = index.coerceAtLeast(0)
        anchorDate.value = anchor
        reviewProgressStore.savePosition(folderId, normalizedIndex, anchor)
    }

    private fun computeNextIndex(current: Int, snapshot: List<PhotoItem>): Int {
        if (snapshot.isEmpty()) {
            return current
        }
        return (current + 1).coerceAtMost(snapshot.lastIndex)
    }

    private fun clampIndex(index: Int): Int {
        val snapshot = photos.value
        if (snapshot.isEmpty()) {
            return 0
        }
        return index.coerceIn(0, snapshot.lastIndex)
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
    }

    enum class ViewerActionInProgress {
        Processing,
        Upload
    }

    private data class DocumentInfo(
        val uri: Uri,
        val displayName: String,
        val parentUri: Uri,
        val size: Long?,
        val lastModified: Long?
    )

    private data class UndoEntryState(
        val type: UserActionType,
        val fromIndex: Int,
        val toIndex: Int,
        val fromUri: String?,
        val toUri: String?,
        val originalParent: String?,
        val displayName: String?
    ) : Serializable

    private enum class UserActionType : Serializable {
        Skip,
        MovedToProcessing,
        EnqueuedUpload
    }

    private fun UserAction.toState(): UndoEntryState = when (this) {
        is UserAction.Skip -> UndoEntryState(
            type = UserActionType.Skip,
            fromIndex = fromIndex,
            toIndex = toIndex,
            fromUri = null,
            toUri = null,
            originalParent = null,
            displayName = null
        )
        is UserAction.MovedToProcessing -> UndoEntryState(
            type = UserActionType.MovedToProcessing,
            fromIndex = fromIndex,
            toIndex = toIndex,
            fromUri = fromUri.toString(),
            toUri = toUri.toString(),
            originalParent = originalParent.toString(),
            displayName = displayName
        )
        is UserAction.EnqueuedUpload -> UndoEntryState(
            type = UserActionType.EnqueuedUpload,
            fromIndex = fromIndex,
            toIndex = toIndex,
            fromUri = uri.toString(),
            toUri = null,
            originalParent = null,
            displayName = null
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
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "photo.jpg"
    }
}
