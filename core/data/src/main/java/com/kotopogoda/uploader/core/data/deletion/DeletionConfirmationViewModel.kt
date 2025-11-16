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
import com.kotopogoda.uploader.core.logging.structuredLog
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
    private var lastPendingCount: Int = -1
    private var activeBatchId: String? = null

    init {
        viewModelScope.launch {
            try {
                val reconciled = confirmDeletionUseCase.reconcilePending()
                if (reconciled > 0) {
                    Timber.tag(TAG).i(
                        structuredLog(
                            "phase" to "ui_state",
                            "event" to "reconcile_applied",
                            "confirmed" to reconciled,
                        )
                    )
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(
                    error,
                    structuredLog(
                        "phase" to "ui_state",
                        "event" to "reconcile_error",
                    )
                )
            }
        }
        viewModelScope.launch {
            pendingItems.collect { items ->
                val newCount = items.size
                val previous = lastPendingCount
                if (previous != newCount) {
                    val attributes = mutableListOf<Pair<String, Any?>>(
                        "phase" to "ui_state",
                        "event" to "pending_count_update",
                        "new_count" to newCount,
                    )
                    if (previous >= 0) {
                        attributes += "old_count" to previous
                    }
                    Timber.tag(TAG).i(structuredLog(*attributes.toTypedArray()))
                    lastPendingCount = newCount
                }
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
        logInProgressStart(batch = null)
        viewModelScope.launch {
            try {
                when (val result = confirmDeletionUseCase.prepare()) {
                    is ConfirmDeletionUseCase.PrepareResult.NoPending -> {
                        Timber.tag(TAG).i(
                            structuredLog(
                                "phase" to "ui_state",
                                "event" to "confirm_prepare_no_pending",
                            )
                        )
                        logInProgressStop("no_pending")
                        confirmationInProgress.value = false
                    }
                    is ConfirmDeletionUseCase.PrepareResult.PermissionRequired -> {
                        Timber.tag(TAG).i(
                            structuredLog(
                                "phase" to "ui_state",
                                "event" to "confirm_permission_requested",
                                "permission_count" to result.permissions.size,
                            )
                        )
                        _events.emit(DeletionConfirmationEvent.RequestPermission(result.permissions))
                    }
                    is ConfirmDeletionUseCase.PrepareResult.Ready -> {
                        pendingBatches = result.batches.toMutableList()
                        accumulatedOutcome = result.initialOutcome
                        Timber.tag(TAG).i(
                            structuredLog(
                                "phase" to "ui_state",
                                "event" to "confirm_prepare_ready",
                                "batch_total" to pendingBatches.size,
                                "initial_confirmed" to result.initialOutcome.confirmedCount,
                                "initial_failed" to result.initialOutcome.failedCount,
                                "initial_skipped" to result.initialOutcome.skippedCount,
                                "initial_freed_bytes" to result.initialOutcome.freedBytes,
                            )
                        )
                        if (pendingBatches.isEmpty()) {
                            emitFinalResult()
                        } else {
                            launchNextBatch()
                        }
                    }
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(
                    error,
                    structuredLog(
                        "phase" to "ui_state",
                        "event" to "confirm_prepare_error",
                    )
                )
                _events.emit(DeletionConfirmationEvent.FinalFailure(error))
                logInProgressStop("prepare_error")
                confirmationInProgress.value = false
                pendingBatches.clear()
                accumulatedOutcome = ConfirmDeletionUseCase.Outcome()
            }
        }
    }

    fun handlePermissionResult(granted: Boolean) {
        if (!granted) {
            Timber.tag(TAG).w(
                structuredLog(
                    "phase" to "ui_state",
                    "event" to "permission_denied",
                )
            )
            _events.tryEmit(DeletionConfirmationEvent.FinalFailure(SecurityException("Разрешения не предоставлены")))
            logInProgressStop("permission_denied")
            confirmationInProgress.value = false
            return
        }
        
        viewModelScope.launch {
            try {
                when (val result = confirmDeletionUseCase.prepare()) {
                    is ConfirmDeletionUseCase.PrepareResult.NoPending -> {
                        Timber.tag(TAG).i(
                            structuredLog(
                                "phase" to "ui_state",
                                "event" to "confirm_prepare_no_pending",
                            )
                        )
                        logInProgressStop("no_pending")
                        confirmationInProgress.value = false
                    }
                    is ConfirmDeletionUseCase.PrepareResult.PermissionRequired -> {
                        Timber.tag(TAG).w(
                            structuredLog(
                                "phase" to "ui_state",
                                "event" to "confirm_permission_missing",
                                "permission_count" to result.permissions.size,
                            )
                        )
                        _events.emit(DeletionConfirmationEvent.FinalFailure(SecurityException("Разрешения требуются")))
                        logInProgressStop("permission_missing")
                        confirmationInProgress.value = false
                    }
                    is ConfirmDeletionUseCase.PrepareResult.Ready -> {
                        pendingBatches = result.batches.toMutableList()
                        accumulatedOutcome = result.initialOutcome
                        Timber.tag(TAG).i(
                            structuredLog(
                                "phase" to "ui_state",
                                "event" to "confirm_prepare_ready",
                                "batch_total" to pendingBatches.size,
                                "initial_confirmed" to result.initialOutcome.confirmedCount,
                                "initial_failed" to result.initialOutcome.failedCount,
                                "initial_skipped" to result.initialOutcome.skippedCount,
                                "initial_freed_bytes" to result.initialOutcome.freedBytes,
                            )
                        )
                        if (pendingBatches.isEmpty()) {
                            emitFinalResult()
                        } else {
                            launchNextBatch()
                        }
                    }
                }
            } catch (error: Exception) {
                Timber.tag(TAG).e(
                    error,
                    structuredLog(
                        "phase" to "ui_state",
                        "event" to "confirm_after_permission_error",
                    )
                )
                _events.emit(DeletionConfirmationEvent.FinalFailure(error))
                logInProgressStop("permission_error")
                confirmationInProgress.value = false
                pendingBatches.clear()
                accumulatedOutcome = ConfirmDeletionUseCase.Outcome()
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
                        Timber.tag(TAG).i(
                            structuredLog(
                                "phase" to "ui_state",
                                "event" to "batch_cancelled",
                                "batch_id" to batch.id,
                                "result_code" to resultCode,
                            )
                        )
                        _events.emit(DeletionConfirmationEvent.FinalFailure(Exception("Отменено пользователем")))
                        logInProgressStop("cancelled")
                        confirmationInProgress.value = false
                        pendingBatches.clear()
                        accumulatedOutcome = ConfirmDeletionUseCase.Outcome()
                    }
                    is ConfirmDeletionUseCase.BatchProcessingResult.Completed -> {
                        Timber.tag(TAG).i(
                            structuredLog(
                                "phase" to "ui_state",
                                "event" to "batch_completed",
                                "batch_id" to batch.id,
                                "result_code" to resultCode,
                                "confirmed" to processingResult.outcome.confirmedCount,
                                "failed" to processingResult.outcome.failedCount,
                                "skipped" to processingResult.outcome.skippedCount,
                                "freed_bytes" to processingResult.outcome.freedBytes,
                            )
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
                Timber.tag(TAG).e(
                    error,
                    structuredLog(
                        "phase" to "ui_state",
                        "event" to "batch_result_error",
                        "batch_id" to batch.id,
                    )
                )
                _events.emit(DeletionConfirmationEvent.FinalFailure(error))
                logInProgressStop("batch_error")
                confirmationInProgress.value = false
                pendingBatches.clear()
                accumulatedOutcome = ConfirmDeletionUseCase.Outcome()
            }
        }
    }

    private suspend fun launchNextBatch() {
        if (pendingBatches.isEmpty()) {
            emitFinalResult()
            return
        }
        
        val batch = pendingBatches.removeAt(0)
        logInProgressStart(batch)
        Timber.tag(TAG).i(
            structuredLog(
                "phase" to "ui_state",
                "event" to "launch_batch",
                "batch_id" to batch.id,
                "batch_index" to batch.index,
                "batch_size" to batch.items.size,
                "batches_remaining" to pendingBatches.size,
            )
        )
        _events.emit(DeletionConfirmationEvent.LaunchBatch(batch))
    }

    private suspend fun emitFinalResult(reason: String = "completed") {
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
        logInProgressStop(reason)
        confirmationInProgress.value = false
        pendingBatches.clear()
        accumulatedOutcome = ConfirmDeletionUseCase.Outcome()
    }

    private fun logInProgressStart(batch: ConfirmDeletionUseCase.DeleteBatch?) {
        activeBatchId = batch?.id
        val attributes = mutableListOf<Pair<String, Any?>>(
            "phase" to "ui_state",
            "event" to "in_progress_start",
        )
        batch?.let {
            attributes += "batch_id" to it.id
            attributes += "batch_size" to it.items.size
            attributes += "batch_index" to it.index
            attributes += "batches_remaining" to pendingBatches.size
        }
        Timber.tag(TAG).i(structuredLog(*attributes.toTypedArray()))
    }

    private fun logInProgressStop(reason: String) {
        val attributes = mutableListOf<Pair<String, Any?>>(
            "phase" to "ui_state",
            "event" to "in_progress_stop",
            "reason" to reason,
        )
        activeBatchId?.let { attributes += "batch_id" to it }
        Timber.tag(TAG).i(structuredLog(*attributes.toTypedArray()))
        activeBatchId = null
    }

    companion object {
        private const val TAG = "DeletionQueue"
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
