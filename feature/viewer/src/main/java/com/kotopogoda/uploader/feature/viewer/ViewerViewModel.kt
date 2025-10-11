package com.kotopogoda.uploader.feature.viewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ViewerViewModel @Inject constructor(
    photoRepository: PhotoRepository,
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

    init {
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
}
