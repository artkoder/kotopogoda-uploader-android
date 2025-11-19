package com.kotopogoda.uploader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.network.connectivity.NetworkMonitor
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.core.network.health.HealthMonitor
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.security.DeviceCreds
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceLogging
import com.kotopogoda.uploader.ml.EnhancerModelProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

@HiltViewModel
class MainViewModel @Inject constructor(
    private val deviceCredsStore: DeviceCredsStore,
    private val healthMonitor: HealthMonitor,
    private val networkMonitor: NetworkMonitor,
    private val settingsRepository: SettingsRepository,
    private val uploadSummaryStarter: UploadSummaryStarter,
    @ApplicationContext private val appContext: Context,
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

    private val hasScheduledEnhancerProbe = AtomicBoolean(false)
    private val enhancerProbeTrigger: Array<Pair<String, Any?>> =
        arrayOf("trigger" to "main_screen_shown")

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

    fun onMainScreenShown() {
        if (!hasScheduledEnhancerProbe.compareAndSet(false, true)) {
            return
        }
        viewModelScope.launch {
            logEnhancerProbeEvent("enhancer_probe_scheduled")
            EnhanceLogging.logEvent("enhancer_probe_scheduled", *enhancerProbeTrigger)
            withContext(Dispatchers.IO) {
                logEnhancerProbeEvent("enhancer_probe_started")
                EnhanceLogging.logEvent("enhancer_probe_started", *enhancerProbeTrigger)
                runCatching { EnhancerModelProbe.run(appContext) }
                    .onFailure { error ->
                        Timber.tag(ENHANCE_PROBE_TAG).e(error, "EnhancerModelProbe завершился с ошибкой")
                    }
            }
        }
    }

    private fun logEnhancerProbeEvent(action: String) {
        Timber.tag(ENHANCE_PROBE_TAG).i(
            UploadLog.message(
                category = "ENHANCE/PROBE",
                action = action,
                details = enhancerProbeTrigger,
            ),
        )
    }

    companion object {
        private const val ENHANCE_PROBE_TAG = "Enhance/Probe"
    }
}

data class MainUiState(
    val deviceCreds: DeviceCreds?,
    val healthState: HealthState,
    val isNetworkValidated: Boolean,
)
