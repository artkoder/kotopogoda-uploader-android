package com.kotopogoda.uploader.feature.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.network.health.HealthMonitor
import com.kotopogoda.uploader.core.network.health.HealthState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class QueueViewModel @Inject constructor(
    healthMonitor: HealthMonitor,
) : ViewModel() {
    val healthState: StateFlow<HealthState> = healthMonitor.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthState.Unknown)
}
