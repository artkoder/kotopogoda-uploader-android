package com.kotopogoda.uploader.ui

import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.BuildConfig
import com.kotopogoda.uploader.R
import com.kotopogoda.uploader.core.logging.LogsShareResult
import com.kotopogoda.uploader.core.logging.LogsSharer
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.di.AppSettingsModule
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val logsSharer: LogsSharer,
    private val uploadEnqueuer: UploadEnqueuer,
    @Named(AppSettingsModule.DOCS_URL) private val docsUrl: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            baseUrlInput = "",
            appVersion = BuildConfig.VERSION_NAME,
            contractVersion = BuildConfig.CONTRACT_VERSION,
            docsUrl = docsUrl,
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsRepository.flow.collect { settings ->
                _uiState.update { state ->
                    state.copy(
                        baseUrlInput = settings.baseUrl,
                        appLoggingEnabled = settings.appLogging,
                        httpLoggingEnabled = settings.httpLogging,
                        isBaseUrlValid = true,
                        isBaseUrlDirty = false,
                    )
                }
            }
        }
    }

    fun onBaseUrlChanged(value: String) {
        _uiState.update { state ->
            state.copy(
                baseUrlInput = value,
                isBaseUrlDirty = true,
                isBaseUrlValid = true,
            )
        }
    }

    fun onApplyBaseUrl() {
        val current = uiState.value.baseUrlInput
        if (!isValidUrl(current)) {
            _uiState.update { it.copy(isBaseUrlValid = false) }
            sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_base_url_invalid))
            return
        }
        viewModelScope.launch {
            runCatching { settingsRepository.setBaseUrl(current.trim()) }
                .onSuccess {
                    _uiState.update { it.copy(isBaseUrlDirty = false, isBaseUrlValid = true) }
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_base_url_saved))
                }
                .onFailure {
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_action_failed))
                }
        }
    }

    fun onAppLoggingChanged(enabled: Boolean) {
        toggleLogging(
            enabled = enabled,
            currentValue = uiState.value.appLoggingEnabled,
            updateState = { value -> _uiState.update { it.copy(appLoggingEnabled = value) } },
            block = { settingsRepository.setAppLogging(enabled) }
        )
    }

    fun onHttpLoggingChanged(enabled: Boolean) {
        toggleLogging(
            enabled = enabled,
            currentValue = uiState.value.httpLoggingEnabled,
            updateState = { value -> _uiState.update { it.copy(httpLoggingEnabled = value) } },
            block = { settingsRepository.setHttpLogging(enabled) }
        )
    }

    private fun toggleLogging(
        enabled: Boolean,
        currentValue: Boolean,
        updateState: (Boolean) -> Unit,
        block: suspend () -> Unit,
    ) {
        if (enabled == currentValue) {
            return
        }
        updateState(enabled)
        viewModelScope.launch {
            runCatching { block() }
                .onFailure {
                    updateState(currentValue)
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_action_failed))
                }
        }
    }

    fun onExportLogs() {
        if (_uiState.value.isExporting) {
            return
        }
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            when (val result = logsSharer.prepareShareIntent()) {
                is LogsShareResult.Success -> {
                    sendEvent(SettingsEvent.ShareLogs(result.intent))
                }
                LogsShareResult.NoLogs -> {
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_logs_empty))
                }
                is LogsShareResult.Error -> {
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_logs_export_failed))
                }
            }
            _uiState.update { it.copy(isExporting = false) }
        }
    }

    fun onClearQueue() {
        viewModelScope.launch {
            runCatching { uploadEnqueuer.cancelAllUploads() }
                .onSuccess {
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_queue_cleared))
                }
                .onFailure {
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_action_failed))
                }
        }
    }

    fun onResetPairingConfirmed() {
        sendEvent(SettingsEvent.ResetPairing)
    }

    fun onOpenDocs() {
        sendEvent(SettingsEvent.OpenDocs(docsUrl))
    }

    private fun isValidUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            return false
        }
        val normalized = if (trimmed.endsWith('/')) trimmed else "$trimmed/"
        val httpUrl = normalized.toHttpUrlOrNull() ?: return false
        return httpUrl.scheme == "http" || httpUrl.scheme == "https"
    }

    private fun sendEvent(event: SettingsEvent) {
        viewModelScope.launch {
            _events.send(event)
        }
    }
}

data class SettingsUiState(
    val baseUrlInput: String,
    val isBaseUrlValid: Boolean = true,
    val isBaseUrlDirty: Boolean = false,
    val appLoggingEnabled: Boolean = false,
    val httpLoggingEnabled: Boolean = false,
    val isExporting: Boolean = false,
    val appVersion: String,
    val contractVersion: String,
    val docsUrl: String,
)

sealed interface SettingsEvent {
    data class ShowMessageRes(@StringRes val resId: Int) : SettingsEvent
    data class ShareLogs(val intent: Intent) : SettingsEvent
    data object ResetPairing : SettingsEvent
    data class OpenDocs(val url: String) : SettingsEvent
}
