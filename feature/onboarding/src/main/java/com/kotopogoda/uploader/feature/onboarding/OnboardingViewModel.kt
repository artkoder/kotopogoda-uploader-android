package com.kotopogoda.uploader.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val folderRepository: FolderRepository
) : ViewModel() {

    private val _uiState: MutableStateFlow<OnboardingUiState> =
        MutableStateFlow(OnboardingUiState.Loading)
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            folderRepository.observeFolder().collect { folder ->
                _uiState.value = if (folder == null) {
                    OnboardingUiState.FolderNotSelected
                } else {
                    OnboardingUiState.FolderSelected(folder.treeUri)
                }
            }
        }
    }

    fun onFolderSelected(treeUri: String) {
        viewModelScope.launch {
            folderRepository.setFolder(treeUri)
        }
    }
}

sealed interface OnboardingUiState {
    data object Loading : OnboardingUiState
    data object FolderNotSelected : OnboardingUiState
    data class FolderSelected(val treeUri: String) : OnboardingUiState
}
