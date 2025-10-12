package com.kotopogoda.uploader.feature.pairing.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import com.kotopogoda.uploader.feature.pairing.data.PairingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: PairingRepository,
    private val credsStore: DeviceCredsStore,
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
        attach(token)
    }

    fun attach(token: String) {
        val normalized = token.trim()
        if (normalized.isEmpty() || normalized == lastProcessedToken) {
            return
        }
        lastProcessedToken = normalized
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = runCatching { repository.attach(normalized) }
            result.fold(
                onSuccess = { response ->
                    credsStore.save(response.deviceId, response.hmacKey)
                    _uiState.value = PairingUiState(isPaired = true)
                    _events.send(PairingEvent.Success)
                },
                onFailure = { error ->
                    lastProcessedToken = null
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = error.message)
                    _events.send(PairingEvent.Error(error.message ?: "Не удалось привязать устройство"))
                },
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }
}

data class PairingUiState(
    val isLoading: Boolean = false,
    val tokenInput: String = "",
    val errorMessage: String? = null,
    val isPaired: Boolean = false,
)

sealed class PairingEvent {
    object Success : PairingEvent()
    data class Error(val message: String) : PairingEvent()
}
