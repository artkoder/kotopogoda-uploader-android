package com.kotopogoda.uploader.navigation

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
class AppStartDestinationViewModel @Inject constructor(
    private val folderRepository: FolderRepository
) : ViewModel() {

    private val _startDestination = MutableStateFlow<AppDestination?>(null)
    val startDestination: StateFlow<AppDestination?> = _startDestination.asStateFlow()

    init {
        viewModelScope.launch {
            val folder = folderRepository.getFolder()
            _startDestination.value = if (folder == null) {
                AppDestination.Onboarding
            } else {
                AppDestination.Viewer
            }
        }
    }
}
