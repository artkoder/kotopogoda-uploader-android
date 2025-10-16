package com.kotopogoda.uploader.navigation

import android.content.Context
import android.content.Intent
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
            val hasPermission = folder?.let { hasPersistedPermission(it.treeUri, it.flags) } ?: false
            _startDestination.value = if (folder == null || !hasPermission) {
                AppDestination.Onboarding
            } else {
                AppDestination.Viewer
            }
        }
    }

    private fun hasPersistedPermission(treeUri: String, flags: Int): Boolean {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        val requiredFlags = maskPersistableFlags(flags)
        if (requiredFlags == 0) {
            return false
        }
        val persisted = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            ?: return false
        val persistedFlags =
            (if (persisted.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (persisted.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        return persistedFlags and requiredFlags == requiredFlags
    }

    private fun maskPersistableFlags(flags: Int): Int {
        val mask = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return flags and mask
    }
}
