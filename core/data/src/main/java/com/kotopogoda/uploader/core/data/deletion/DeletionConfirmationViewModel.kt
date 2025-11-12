package com.kotopogoda.uploader.core.data.deletion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class DeletionConfirmationViewModel @Inject constructor(
    private val deletionQueueRepository: DeletionQueueRepository,
) : ViewModel() {

    private val pendingItems: StateFlow<List<DeletionItem>> =
        deletionQueueRepository.observePending()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = emptyList(),
            )

    private val confirmationInProgress = MutableStateFlow(false)
    private val _events = MutableSharedFlow<DeletionConfirmationEvent>(extraBufferCapacity = 1)

    val uiState: StateFlow<DeletionConfirmationUiState> = combine(
        pendingItems,
        confirmationInProgress,
    ) { items, inProgress ->
        val pendingBytes = items.sumOf { it.sizeBytes?.takeIf { size -> size > 0 } ?: 0L }
        DeletionConfirmationUiState(
            pendingCount = items.size,
            pendingBytesApprox = pendingBytes,
            inProgress = inProgress,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = DeletionConfirmationUiState(),
    )

    val events: SharedFlow<DeletionConfirmationEvent> = _events.asSharedFlow()

    fun confirmPending() {
        if (confirmationInProgress.value) {
            return
        }
        val itemsSnapshot = pendingItems.value
        if (itemsSnapshot.isEmpty()) {
            return
        }
        confirmationInProgress.value = true
        viewModelScope.launch {
            val ids = itemsSnapshot.map { it.mediaId }
            try {
                val confirmed = deletionQueueRepository.markConfirmed(ids)
                val approxFreedBytes = if (confirmed > 0) {
                    itemsSnapshot
                        .take(confirmed.coerceAtMost(itemsSnapshot.size))
                        .sumOf { item -> item.sizeBytes?.takeIf { size -> size > 0 } ?: 0L }
                } else {
                    0L
                }
                _events.emit(
                    DeletionConfirmationEvent.ConfirmationSuccess(
                        confirmedCount = confirmed,
                        totalBytes = approxFreedBytes,
                    )
                )
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Не удалось подтвердить удаление")
                _events.emit(DeletionConfirmationEvent.ConfirmationFailed(error))
            } finally {
                confirmationInProgress.value = false
            }
        }
    }

    companion object {
        private const val TAG = "DeletionConfirm"
    }
}

data class DeletionConfirmationUiState(
    val pendingCount: Int = 0,
    val pendingBytesApprox: Long = 0L,
    val inProgress: Boolean = false,
) {
    val isConfirmEnabled: Boolean
        get() = pendingCount > 0 && !inProgress
}

sealed interface DeletionConfirmationEvent {
    data class ConfirmationSuccess(val confirmedCount: Int, val totalBytes: Long) : DeletionConfirmationEvent
    data class ConfirmationFailed(val throwable: Throwable) : DeletionConfirmationEvent
}
