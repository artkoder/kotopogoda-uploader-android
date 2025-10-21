package com.kotopogoda.uploader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.network.connectivity.NetworkMonitor
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.core.network.health.HealthMonitor
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.security.DeviceCreds
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val deviceCredsStore: DeviceCredsStore,
    private val healthMonitor: HealthMonitor,
    private val networkMonitor: NetworkMonitor,
    private val settingsRepository: SettingsRepository,
    private val uploadSummaryStarter: UploadSummaryStarter,
) : ViewModel() {

    val uiState: StateFlow<MainUiState> = combine(
        deviceCredsStore.credsFlow,
        healthMonitor.state,
        networkMonitor.isNetworkValidated,
    ) { creds, health, isNetworkValidated ->
        MainUiState(
            deviceCreds = creds,
            healthState = health,
            isNetworkValidated = isNetworkValidated,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(
            deviceCreds = null,
            healthState = HealthState.Unknown,
            isNetworkValidated = false,
        ),
    )

    init {
        healthMonitor.start(viewModelScope)
        viewModelScope.launch {
            settingsRepository.flow
                .map { it.persistentQueueNotification }
                .distinctUntilChanged()
                .collect { persistent ->
                    if (persistent) {
                        uploadSummaryStarter.ensureRunning()
                    }
                }
        }
    }

    fun clearPairing() {
        viewModelScope.launch {
            deviceCredsStore.clear()
        }
    }
}

data class MainUiState(
    val deviceCreds: DeviceCreds?,
    val healthState: HealthState,
    val isNetworkValidated: Boolean,
)
