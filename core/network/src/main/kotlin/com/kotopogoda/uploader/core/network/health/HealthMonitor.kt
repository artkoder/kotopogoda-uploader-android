package com.kotopogoda.uploader.core.network.health

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class HealthMonitor @Inject constructor(
    private val healthRepository: HealthRepository,
) {

    private val started = AtomicBoolean(false)
    private val _state = MutableStateFlow(HealthState.Unknown)
    val state: StateFlow<HealthState> = _state.asStateFlow()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(scope: CoroutineScope, intervalMillis: Long = DEFAULT_INTERVAL_MS) {
        if (!started.compareAndSet(false, true)) {
            return
        }
        scope.launchCheckLoop(intervalMillis)
    }

    suspend fun checkOnce(): HealthState = updateState()

    fun refreshNow() {
        refreshScope.launch {
            updateState()
        }
    }

    private fun CoroutineScope.launchCheckLoop(intervalMillis: Long) = launch {
        while (isActive) {
            updateState()
            delay(intervalMillis)
        }
    }

    private suspend fun updateState(): HealthState {
        val repositoryResult = healthRepository.check()
        val newState = HealthState(
            status = repositoryResult.status,
            lastCheckedAt = repositoryResult.checkedAt,
            message = repositoryResult.message,
            latencyMillis = repositoryResult.latencyMillis,
        )
        _state.value = newState
        return newState
    }

    companion object {
        private const val DEFAULT_INTERVAL_MS: Long = 30_000L
    }
}
