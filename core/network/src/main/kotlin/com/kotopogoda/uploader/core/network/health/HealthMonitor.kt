package com.kotopogoda.uploader.core.network.health

import com.kotopogoda.uploader.core.network.api.HealthApi
import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class HealthMonitor @Inject constructor(
    private val networkClientProvider: NetworkClientProvider,
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

    suspend fun checkOnce() {
        updateState()
    }

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

    private suspend fun updateState() {
        val now = Instant.now()
        val result = withContext(Dispatchers.IO) {
            val api = networkClientProvider.create(HealthApi::class.java)
            runCatching { api.health() }
        }

        val newState = result.fold(
            onSuccess = { response ->
                when (response.status.lowercase()) {
                    "online" -> HealthState(HealthStatus.ONLINE, now, response.message)
                    "degraded" -> HealthState(HealthStatus.DEGRADED, now, response.message)
                    else -> HealthState(HealthStatus.ONLINE, now, response.message)
                }
            },
            onFailure = { error ->
                HealthState(HealthStatus.OFFLINE, now, error.message)
            },
        )
        _state.value = newState
    }

    companion object {
        private const val DEFAULT_INTERVAL_MS: Long = 30_000L
    }
}
