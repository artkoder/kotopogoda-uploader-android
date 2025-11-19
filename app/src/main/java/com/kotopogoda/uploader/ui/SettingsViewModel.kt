package com.kotopogoda.uploader.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.BuildConfig
import com.kotopogoda.uploader.R
import com.kotopogoda.uploader.core.logging.LogManager
import com.kotopogoda.uploader.core.logging.LogsExportResult
import com.kotopogoda.uploader.core.logging.LogsExporter
import com.kotopogoda.uploader.core.logging.structuredLog
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.di.AppSettingsModule
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceController
import com.kotopogoda.uploader.notifications.NotificationPermissionChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val logManager: LogManager,
    private val logsExporter: LogsExporter,
    private val uploadEnqueuer: UploadEnqueuer,
    private val notificationPermissionChecker: NotificationPermissionChecker,
    @Named(AppSettingsModule.DOCS_URL) private val docsUrl: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            baseUrlInput = "",
            appVersion = BuildConfig.VERSION_NAME,
            contractVersion = BuildConfig.CONTRACT_VERSION,
            docsUrl = docsUrl,
            queueNotificationPersistent = false,
            queueNotificationPermissionGranted = notificationPermissionChecker.canPostNotifications(),
            isQueueNotificationToggleEnabled = notificationPermissionChecker.canPostNotifications(),
            logsDirectoryPath = logsExporter.publicDirectoryDisplayPath(),
            previewQuality = com.kotopogoda.uploader.core.settings.PreviewQuality.BALANCED,
            autoDeleteAfterUpload = true,
            forceCpuForEnhancement = false,
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
                        queueNotificationPersistent = settings.persistentQueueNotification,
                        previewQuality = settings.previewQuality,
                        autoDeleteAfterUpload = settings.autoDeleteAfterUpload,
                        forceCpuForEnhancement = settings.forceCpuForEnhancement,
                        isBaseUrlValid = true,
                        isBaseUrlDirty = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            notificationPermissionChecker.permissionFlow().collect { granted ->
                _uiState.update { state ->
                    val currentPersistent = if (granted) {
                        state.queueNotificationPersistent
                    } else {
                        false
                    }
                    state.copy(
                        queueNotificationPermissionGranted = granted,
                        isQueueNotificationToggleEnabled = granted,
                        queueNotificationPersistent = currentPersistent,
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
        togglePreference(
            enabled = enabled,
            currentValue = uiState.value.appLoggingEnabled,
            updateState = { value -> _uiState.update { it.copy(appLoggingEnabled = value) } },
            block = { settingsRepository.setAppLogging(enabled) }
        )
    }

    fun onHttpLoggingChanged(enabled: Boolean) {
        togglePreference(
            enabled = enabled,
            currentValue = uiState.value.httpLoggingEnabled,
            updateState = { value -> _uiState.update { it.copy(httpLoggingEnabled = value) } },
            block = { settingsRepository.setHttpLogging(enabled) }
        )
    }

    fun onQueueNotificationChanged(enabled: Boolean) {
        val current = uiState.value.queueNotificationPersistent
        if (enabled == current) {
            return
        }
        if (!notificationPermissionChecker.canPostNotifications()) {
            if (enabled) {
                sendEvent(SettingsEvent.RequestNotificationPermission)
            }
            _uiState.update { it.copy(queueNotificationPersistent = false) }
            viewModelScope.launch {
                runCatching { settingsRepository.setPersistentQueueNotification(false) }
            }
            return
        }
        _uiState.update { it.copy(queueNotificationPersistent = enabled) }
        viewModelScope.launch {
            runCatching { settingsRepository.setPersistentQueueNotification(enabled) }
                .onFailure {
                    _uiState.update { it.copy(queueNotificationPersistent = current) }
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_action_failed))
                }
        }
    }

    private fun togglePreference(
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

    fun onRequestQueueNotificationPermission() {
        if (notificationPermissionChecker.canPostNotifications()) {
            notificationPermissionChecker.refresh()
            return
        }
        sendEvent(SettingsEvent.RequestNotificationPermission)
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        notificationPermissionChecker.refresh()
        if (!granted) {
            viewModelScope.launch {
                runCatching { settingsRepository.setPersistentQueueNotification(false) }
            }
            sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_queue_notification_permission_denied))
        }
    }

    fun onExportLogs() {
        if (_uiState.value.isExporting) {
            return
        }
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            when (val result = logsExporter.export()) {
                is LogsExportResult.Success -> {
                    sendEvent(SettingsEvent.ShowLogsExported(result.displayPath, result.uri))
                }
                LogsExportResult.NoLogs -> {
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_logs_empty))
                }
                is LogsExportResult.Error -> {
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

    fun onPreviewQualityChanged(quality: com.kotopogoda.uploader.core.settings.PreviewQuality) {
        val current = uiState.value.previewQuality
        if (quality == current) {
            return
        }
        _uiState.update { it.copy(previewQuality = quality) }
        viewModelScope.launch {
            runCatching { settingsRepository.setPreviewQuality(quality) }
                .onFailure {
                    _uiState.update { it.copy(previewQuality = current) }
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_action_failed))
                }
        }
    }

    fun onAutoDeleteAfterUploadChanged(enabled: Boolean) {
        val current = uiState.value.autoDeleteAfterUpload
        if (enabled == current) {
            return
        }
        Timber.tag(DELETION_QUEUE_TAG).i(
            structuredLog(
                "phase" to "settings",
                "event" to "autodelete_setting_changed",
                "value" to enabled,
                "previous" to current,
            )
        )
        togglePreference(
            enabled = enabled,
            currentValue = current,
            updateState = { value -> _uiState.update { it.copy(autoDeleteAfterUpload = value) } },
            block = { settingsRepository.setAutoDeleteAfterUpload(enabled) }
        )
    }

    fun onForceCpuForEnhancementChanged(enabled: Boolean) {
        val current = uiState.value.forceCpuForEnhancement
        if (enabled == current) {
            return
        }
        _uiState.update { it.copy(forceCpuForEnhancement = enabled) }
        NativeEnhanceController.setForceCpuOverride(enabled.takeIf { it })
        viewModelScope.launch {
            runCatching { settingsRepository.setForceCpuForEnhancement(enabled) }
                .onFailure {
                    _uiState.update { it.copy(forceCpuForEnhancement = current) }
                    NativeEnhanceController.setForceCpuOverride(current.takeIf { it })
                    sendEvent(SettingsEvent.ShowMessageRes(R.string.settings_snackbar_action_failed))
                }
        }
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
    val appLoggingEnabled: Boolean = true,
    val httpLoggingEnabled: Boolean = true,
    val queueNotificationPersistent: Boolean = false,
    val queueNotificationPermissionGranted: Boolean = true,
    val isQueueNotificationToggleEnabled: Boolean = true,
    val isExporting: Boolean = false,
    val appVersion: String,
    val contractVersion: String,
    val docsUrl: String,
    val logsDirectoryPath: String,
    val previewQuality: com.kotopogoda.uploader.core.settings.PreviewQuality,
    val autoDeleteAfterUpload: Boolean = true,
    val forceCpuForEnhancement: Boolean = false,
)

sealed interface SettingsEvent {
    data class ShowMessageRes(@StringRes val resId: Int) : SettingsEvent
    data class ShowLogsExported(val path: String, val uri: Uri) : SettingsEvent
    data object ResetPairing : SettingsEvent
    data class OpenDocs(val url: String) : SettingsEvent
    data object RequestNotificationPermission : SettingsEvent
}

private const val DELETION_QUEUE_TAG = "DeletionQueue"
