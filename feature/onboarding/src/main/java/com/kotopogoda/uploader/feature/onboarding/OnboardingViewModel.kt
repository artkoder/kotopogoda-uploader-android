package com.kotopogoda.uploader.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.indexer.IndexerRepository
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.settings.ReviewPosition
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import com.kotopogoda.uploader.core.settings.reviewProgressFolderId
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val folderRepository: FolderRepository,
    private val photoRepository: PhotoRepository,
    private val reviewProgressStore: ReviewProgressStore,
    private val indexerRepository: IndexerRepository
) : ViewModel() {

    private val _uiState: MutableStateFlow<OnboardingUiState> =
        MutableStateFlow(OnboardingUiState.Loading)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<OnboardingEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<OnboardingEvent> = _events.asSharedFlow()

    private var currentFolderId: String? = null
    private var currentFolderRecordId: Int? = null
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            folderRepository.observeFolder().collect { folder ->
                if (folder == null) {
                    currentFolderId = null
                    currentFolderRecordId = null
                    scanJob?.cancel()
                    _uiState.value = OnboardingUiState.FolderNotSelected
                } else {
                    val folderId = reviewProgressFolderId(folder.treeUri)
                    val previousState = _uiState.value as? OnboardingUiState.FolderSelected
                    val isFolderChanged = currentFolderId != folderId || currentFolderRecordId != folder.id
                    currentFolderId = folderId
                    currentFolderRecordId = folder.id
                    val progress = reviewProgressStore.loadPosition(folderId)
                    val photoCount = photoRepository.countAll()
                    val scanState = if (isFolderChanged) {
                        OnboardingScanState.Idle
                    } else {
                        previousState?.scanState ?: OnboardingScanState.Idle
                    }
                    _uiState.value = OnboardingUiState.FolderSelected(
                        treeUri = folder.treeUri,
                        flags = folder.flags,
                        photoCount = photoCount,
                        progress = progress,
                        scanState = scanState
                    )
                    if (indexerRepository.isIndexerEnabled && (isFolderChanged || previousState == null)) {
                        startScan()
                    }
                }
            }
        }
    }

    fun onFolderSelected(treeUri: String, flags: Int) {
        viewModelScope.launch {
            folderRepository.setFolder(treeUri, flags)
        }
    }

    fun onStartReview(option: ReviewStartOption, selectedDate: Instant?) {
        val state = _uiState.value as? OnboardingUiState.FolderSelected ?: return
        currentFolderId ?: return
        viewModelScope.launch {
            val targetIndex = when (option) {
                ReviewStartOption.CONTINUE -> {
                    val anchor = state.progress?.anchorDate
                    if (anchor != null) {
                        photoRepository.findIndexAtOrAfter(anchor)
                    } else {
                        val stored = state.progress?.index ?: 0
                        photoRepository.clampIndex(stored)
                    }
                }

                ReviewStartOption.NEW -> {
                    val anchor = state.progress?.anchorDate
                    if (anchor != null) {
                        val anchorIndex = photoRepository.findIndexAtOrAfter(anchor)
                        photoRepository.clampIndex(anchorIndex + 1)
                    } else {
                        val raw = (state.progress?.index ?: -1) + 1
                        photoRepository.clampIndex(raw)
                    }
                }

                ReviewStartOption.DATE -> {
                    val date = selectedDate ?: Instant.EPOCH
                    photoRepository.findIndexAtOrAfter(date)
                }
            }
            _events.emit(OnboardingEvent.OpenViewer(targetIndex))
        }
    }

    fun onResetProgress() {
        val folderId = currentFolderId ?: return
        viewModelScope.launch {
            reviewProgressStore.clear(folderId)
            refreshFolderState()
        }
    }

    fun onResetAnchor() {
        val folderId = currentFolderId ?: return
        val state = _uiState.value as? OnboardingUiState.FolderSelected ?: return
        val index = state.progress?.index ?: return
        viewModelScope.launch {
            reviewProgressStore.savePosition(folderId, index, anchorDate = null)
            refreshFolderState()
        }
    }

    private suspend fun refreshFolderState() {
        val state = _uiState.value as? OnboardingUiState.FolderSelected ?: return
        val folderId = currentFolderId ?: return
        val progress = reviewProgressStore.loadPosition(folderId)
        val photoCount = photoRepository.countAll()
        _uiState.value = state.copy(photoCount = photoCount, progress = progress)
    }

    private fun startScan() {
        if (!indexerRepository.isIndexerEnabled) {
            return
        }
        scanJob?.cancel()
        val folderId = currentFolderId ?: return
        scanJob = viewModelScope.launch {
            var lastProgress: IndexerRepository.ScanProgress? = null
            try {
                updateScanState(OnboardingScanState.InProgress(progress = null))
                indexerRepository.scanAll().collect { progress ->
                    lastProgress = progress
                    updateScanState(OnboardingScanState.InProgress(progress))
                }
                refreshFolderState()
                updateScanState(OnboardingScanState.Completed(lastProgress))
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                updateScanState(
                    OnboardingScanState.Failed(
                        error.message ?: error::class.java.simpleName
                    )
                )
            }
        }
    }

    private fun updateScanState(scanState: OnboardingScanState) {
        val state = _uiState.value as? OnboardingUiState.FolderSelected ?: return
        _uiState.value = state.copy(scanState = scanState)
    }
}

sealed interface OnboardingUiState {
    data object Loading : OnboardingUiState
    data object FolderNotSelected : OnboardingUiState
    data class FolderSelected(
        val treeUri: String,
        val flags: Int,
        val photoCount: Int,
        val progress: ReviewPosition?,
        val scanState: OnboardingScanState
    ) : OnboardingUiState
}

sealed interface OnboardingScanState {
    data object Idle : OnboardingScanState
    data class InProgress(val progress: IndexerRepository.ScanProgress?) : OnboardingScanState
    data class Completed(val progress: IndexerRepository.ScanProgress?) : OnboardingScanState
    data class Failed(val message: String) : OnboardingScanState
}

enum class ReviewStartOption {
    CONTINUE,
    NEW,
    DATE
}

sealed interface OnboardingEvent {
    data class OpenViewer(val startIndex: Int) : OnboardingEvent
}
