package com.kotopogoda.uploader.core.data.deletion

import android.app.Activity
import android.content.Intent
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
    private val confirmDeletionUseCase: ConfirmDeletionUseCase,
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
    
    private var pendingBatches = mutableListOf<ConfirmDeletionUseCase.DeleteBatch>()
    private var accumulatedOutcome = ConfirmDeletionUseCase.Outcome()

    init {
        viewModelScope.launch {
            try {
                val reconciled = confirmDeletionUseCase.reconcilePending()
                if (reconciled > 0) {
                    Timber.tag(TAG).i("Реконсиляция подтвердила %d элементов", reconciled)
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Ошибка реконсиляции очереди удаления")
            }
        }
    }

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
        confirmationInProgress.value = true
        viewModelScope.launch {
            try {
                when (val result = confirmDeletionUseCase.prepare()) {
                    is ConfirmDeletionUseCase.PrepareResult.NoPending -> {
                        Timber.tag(TAG).i("Нет элементов для подтверждения")
                        confirmationInProgress.value = false
                    }
                    is ConfirmDeletionUseCase.PrepareResult.PermissionRequired -> {
                        _events.emit(DeletionConfirmationEvent.RequestPermission(result.permissions))
                    }
                    is ConfirmDeletionUseCase.PrepareResult.Ready -> {
                        pendingBatches = result.batches.toMutableList()
                        accumulatedOutcome = result.initialOutcome
                        
                        if (pendingBatches.isEmpty()) {
                            emitFinalResult()
                        } else {
                            launchNextBatch()
                        }
                    }
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Ошибка при подготовке удаления")
                _events.emit(DeletionConfirmationEvent.FinalFailure(error))
                confirmationInProgress.value = false
            }
        }
    }

    fun handlePermissionResult(granted: Boolean) {
        if (!granted) {
            Timber.tag(TAG).w("Разрешения не предоставлены")
            _events.tryEmit(DeletionConfirmationEvent.FinalFailure(SecurityException("Разрешения не предоставлены")))
            confirmationInProgress.value = false
            return
        }
        
        viewModelScope.launch {
            try {
                when (val result = confirmDeletionUseCase.prepare()) {
                    is ConfirmDeletionUseCase.PrepareResult.NoPending -> {
                        confirmationInProgress.value = false
                    }
                    is ConfirmDeletionUseCase.PrepareResult.PermissionRequired -> {
                        Timber.tag(TAG).w("Разрешения все еще требуются")
                        _events.emit(DeletionConfirmationEvent.FinalFailure(SecurityException("Разрешения требуются")))
                        confirmationInProgress.value = false
                    }
                    is ConfirmDeletionUseCase.PrepareResult.Ready -> {
                        pendingBatches = result.batches.toMutableList()
                        accumulatedOutcome = result.initialOutcome
                        
                        if (pendingBatches.isEmpty()) {
                            emitFinalResult()
                        } else {
                            launchNextBatch()
                        }
                    }
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Ошибка после предоставления разрешений")
                _events.emit(DeletionConfirmationEvent.FinalFailure(error))
                confirmationInProgress.value = false
            }
        }
    }

    fun handleBatchResult(
        batch: ConfirmDeletionUseCase.DeleteBatch,
        resultCode: Int,
        data: Intent?
    ) {
        viewModelScope.launch {
            try {
                val processingResult = confirmDeletionUseCase.handleBatchResult(batch, resultCode, data)
                
                when (processingResult) {
                    is ConfirmDeletionUseCase.BatchProcessingResult.Cancelled -> {
                        Timber.tag(TAG).i("Батч отменен пользователем")
                        _events.emit(DeletionConfirmationEvent.FinalFailure(Exception("Отменено пользователем")))
                        confirmationInProgress.value = false
                        pendingBatches.clear()
                    }
                    is ConfirmDeletionUseCase.BatchProcessingResult.Completed -> {
                        Timber.tag(TAG).i(
                            "Системное подтверждение батча %s завершено: confirmed=%d, failed=%d, skipped=%d, freed=%d",
                            batch.id,
                            processingResult.outcome.confirmedCount,
                            processingResult.outcome.failedCount,
                            processingResult.outcome.skippedCount,
                            processingResult.outcome.freedBytes,
                        )
                        accumulatedOutcome += processingResult.outcome
                        
                        if (pendingBatches.isEmpty()) {
                            emitFinalResult()
                        } else {
                            launchNextBatch()
                        }
                    }
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "Ошибка обработки результата батча")
                _events.emit(DeletionConfirmationEvent.FinalFailure(error))
                confirmationInProgress.value = false
                pendingBatches.clear()
            }
        }
    }

    private suspend fun launchNextBatch() {
        if (pendingBatches.isEmpty()) {
            emitFinalResult()
            return
        }
        
        val batch = pendingBatches.removeAt(0)
        Timber.tag(TAG).i(
            "Запуск системного подтверждения удаления: batchId=%s, index=%d, size=%d",
            batch.id,
            batch.index,
            batch.items.size,
        )
        _events.emit(DeletionConfirmationEvent.LaunchBatch(batch))
    }

    private suspend fun emitFinalResult() {
        if (accumulatedOutcome.hasChanges) {
            _events.emit(
                DeletionConfirmationEvent.FinalSuccess(
                    confirmedCount = accumulatedOutcome.confirmedCount,
                    freedBytes = accumulatedOutcome.freedBytes,
                    failedCount = accumulatedOutcome.failedCount,
                    skippedCount = accumulatedOutcome.skippedCount,
                )
            )
        }
        confirmationInProgress.value = false
        pendingBatches.clear()
        accumulatedOutcome = ConfirmDeletionUseCase.Outcome()
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
    data class RequestPermission(val permissions: Set<String>) : DeletionConfirmationEvent
    data class LaunchBatch(val batch: ConfirmDeletionUseCase.DeleteBatch) : DeletionConfirmationEvent
    data class FinalSuccess(
        val confirmedCount: Int,
        val freedBytes: Long,
        val failedCount: Int,
        val skippedCount: Int,
    ) : DeletionConfirmationEvent
    data class FinalFailure(val throwable: Throwable) : DeletionConfirmationEvent
}
