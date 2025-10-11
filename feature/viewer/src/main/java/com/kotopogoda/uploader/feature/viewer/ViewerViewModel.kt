package com.kotopogoda.uploader.feature.viewer

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.data.indexer.IndexerRepository
import com.kotopogoda.uploader.core.data.indexer.IndexerRepository.ScanProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val indexerRepository: IndexerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        if (_uiState.value.isScanning) {
            return
        }
        scanJob?.cancel()
        _uiState.update { it.copy(isScanning = true, progress = ScanProgress(), errorMessage = null) }
        scanJob = viewModelScope.launch {
            indexerRepository.scanAll()
                .onEach { progress ->
                    _uiState.update { current -> current.copy(progress = progress) }
                }
                .catch { error ->
                    Log.w(TAG, "Scan failed", error)
                    val message = error.localizedMessage ?: error.javaClass.simpleName
                    _uiState.update { current -> current.copy(errorMessage = message) }
                }
                .onCompletion {
                    scanJob = null
                    _uiState.update { current -> current.copy(isScanning = false) }
                }
                .collect()
        }
    }

    fun cancelScan() {
        if (scanJob?.isActive != true) {
            return
        }
        scanJob?.cancel()
        scanJob = null
        _uiState.update { it.copy(isScanning = false) }
    }

    companion object {
        private const val TAG = "ViewerViewModel"
    }
}

data class ViewerUiState(
    val isScanning: Boolean = false,
    val progress: ScanProgress = ScanProgress(),
    val errorMessage: String? = null
)
