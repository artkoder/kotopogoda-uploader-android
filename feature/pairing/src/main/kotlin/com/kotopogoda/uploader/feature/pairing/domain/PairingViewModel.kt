package com.kotopogoda.uploader.feature.pairing.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import com.kotopogoda.uploader.feature.pairing.data.PairingException
import com.kotopogoda.uploader.feature.pairing.data.PairingRepository
import com.kotopogoda.uploader.feature.pairing.logging.PairingLogExportResult
import com.kotopogoda.uploader.feature.pairing.logging.PairingLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.text.Charsets

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
    private val credsStore: DeviceCredsStore,
    private val pairingLogger: PairingLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    private val _events = Channel<PairingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var lastProcessedToken: String? = null

    fun onTokenInputChanged(token: String) {
        _uiState.value = _uiState.value.copy(tokenInput = token)
    }

    fun attachWithCurrentInput() {
        val token = _uiState.value.tokenInput.trim()
        if (token.isEmpty()) {
            viewModelScope.launch {
                _events.send(PairingEvent.Error("Введите токен"))
            }
            return
        }
        pairingLogger.log("Manual attach requested fingerprint=${fingerprint(token)}")
        attach(token)
    }

    fun attach(token: String) {
        val normalized = token.trim()
        if (normalized.isEmpty() || normalized == lastProcessedToken) {
            if (normalized == lastProcessedToken) {
                pairingLogger.log("Skipping duplicate token fingerprint=${fingerprint(normalized)}")
            }
            return
        }
        lastProcessedToken = normalized
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            pairingLogger.log("Attempting attach fingerprint=${fingerprint(normalized)}")
            val result = runCatching { repository.attach(normalized) }
            result.fold(
                onSuccess = { response ->
                    pairingLogger.log("Attach succeeded device=${response.deviceId.takeLast(4)}")
                    credsStore.save(response.deviceId, response.hmacKey)
                    _uiState.value = PairingUiState(isPaired = true)
                    _events.send(PairingEvent.Success)
                },
                onFailure = { error ->
                    lastProcessedToken = null
                    pairingLogger.log("Attach failed reason=${error.message ?: error::class.simpleName}")
                    val message = when (error) {
                        is PairingException -> error.message
                        else -> error.message
                    } ?: "Не удалось привязать устройство"
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = message)
                    _events.send(PairingEvent.Error(message))
                },
            )
        }
    }

    fun onParsingError(rawValue: String?) {
        val fingerprint = rawValue?.let { fingerprint(it) } ?: "null"
        pairingLogger.log("QR parse failed fingerprint=$fingerprint length=${rawValue?.length ?: 0}")
        viewModelScope.launch {
            _events.send(PairingEvent.Error("Не удалось распознать токен"))
        }
    }

    fun exportLogs() {
        if (_uiState.value.isExportingLogs) {
            return
        }
        viewModelScope.launch {
            pairingLogger.log("Log export requested")
            _uiState.value = _uiState.value.copy(isExportingLogs = true)
            when (val result = pairingLogger.exportToDownloads()) {
                is PairingLogExportResult.Success -> {
                    pairingLogger.log("Log export successful uri=${result.uri}")
                    _events.send(PairingEvent.LogsExported("Логи сохранены в загрузках"))
                }
                PairingLogExportResult.NoLogs -> {
                    pairingLogger.log("Log export skipped: no logs")
                    _events.send(PairingEvent.Info("Логов пока нет"))
                }
                is PairingLogExportResult.Error -> {
                    pairingLogger.log("Log export failed reason=${result.throwable.message}")
                    _events.send(PairingEvent.Error("Не удалось сохранить логи"))
                }
            }
            _uiState.value = _uiState.value.copy(isExportingLogs = false)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    private fun fingerprint(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return buildString(8) {
            for (index in 0 until 4) {
                val byteValue = digest[index].toInt() and 0xFF
                append(byteValue.toString(16).padStart(2, '0'))
            }
        }
    }
}

data class PairingUiState(
    val isLoading: Boolean = false,
    val tokenInput: String = "",
    val errorMessage: String? = null,
    val isPaired: Boolean = false,
    val isExportingLogs: Boolean = false,
)

sealed class PairingEvent {
    object Success : PairingEvent()
    data class Error(val message: String) : PairingEvent()
    data class Info(val message: String) : PairingEvent()
    data class LogsExported(val message: String) : PairingEvent()
}
