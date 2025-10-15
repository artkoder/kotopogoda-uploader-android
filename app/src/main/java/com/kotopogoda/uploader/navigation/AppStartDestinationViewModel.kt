package com.kotopogoda.uploader.navigation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AppStartDestinationViewModel @Inject constructor(
    private val folderRepository: FolderRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _startDestination = MutableStateFlow<AppDestination?>(null)
    val startDestination: StateFlow<AppDestination?> = _startDestination.asStateFlow()

    init {
        viewModelScope.launch {
            val folder = folderRepository.getFolder()
            val hasPermission = folder?.treeUri?.let(::hasPersistedPermission) ?: false
            _startDestination.value = if (folder == null || !hasPermission) {
                AppDestination.Onboarding
            } else {
                AppDestination.Viewer
            }
        }
    }

    private fun hasPersistedPermission(treeUri: String): Boolean {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri }
    }
}
