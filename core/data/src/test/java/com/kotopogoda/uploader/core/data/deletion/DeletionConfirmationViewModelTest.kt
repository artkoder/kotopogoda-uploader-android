package com.kotopogoda.uploader.core.data.deletion

import android.net.Uri
import com.kotopogoda.uploader.core.logging.test.MainDispatcherRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeletionConfirmationViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @MockK
    lateinit var repository: DeletionQueueRepository
    
    @MockK
    lateinit var confirmDeletionUseCase: ConfirmDeletionUseCase

    private val pendingFlow = MutableStateFlow<List<DeletionItem>>(emptyList())

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        every { repository.observePending() } returns pendingFlow
        coEvery { confirmDeletionUseCase.reconcilePending() } returns 0
    }

    @Test
    fun `uiState reflects pending queue`() = runTest {
        val viewModel = DeletionConfirmationViewModel(repository, confirmDeletionUseCase)

        assertEquals(0, viewModel.uiState.value.pendingCount)
        assertFalse(viewModel.uiState.value.inProgress)

        val items = listOf(
            pendingItem(id = 1L, sizeBytes = 1_024L),
            pendingItem(id = 2L, sizeBytes = 2_048L),
        )
        pendingFlow.value = items

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.pendingCount)
        assertEquals(3_072L, state.pendingBytesApprox)
        assertTrue(state.isConfirmEnabled)
    }

    @Test
    fun `pending counter resets when queue cleared`() = runTest {
        val viewModel = DeletionConfirmationViewModel(repository, confirmDeletionUseCase)

        val items = listOf(
            pendingItem(id = 11L, sizeBytes = 1_024L),
            pendingItem(id = 12L, sizeBytes = 2_048L),
        )
        pendingFlow.value = items
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.pendingCount)

        pendingFlow.value = emptyList()
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.pendingCount)
        assertFalse(viewModel.uiState.value.isConfirmEnabled)
    }

    @Test
    fun `confirmPending with ready batches emits LaunchBatch events`() = runTest {
        val items = listOf(
            pendingItem(id = 4L, sizeBytes = 512L),
            pendingItem(id = 5L, sizeBytes = 256L),
        )
        every { repository.observePending() } returns pendingFlow.apply { value = items }
        
        val batch = createMockBatch("batch-1", 0)
        coEvery { confirmDeletionUseCase.prepare() } returns ConfirmDeletionUseCase.PrepareResult.Ready(
            batches = listOf(batch),
            initialOutcome = ConfirmDeletionUseCase.Outcome()
        )

        val viewModel = DeletionConfirmationViewModel(repository, confirmDeletionUseCase)

        advanceUntilIdle()

        val eventDeferred = backgroundScope.async { viewModel.events.first() }

        viewModel.confirmPending()

        assertTrue(viewModel.uiState.value.inProgress)

        advanceUntilIdle()

        val event = eventDeferred.await()
        assertIs<DeletionConfirmationEvent.LaunchBatch>(event)
    }

    @Test
    fun `confirmPending with permission required emits RequestPermission`() = runTest {
        val items = listOf(pendingItem(id = 10L, sizeBytes = 1_000L))
        every { repository.observePending() } returns pendingFlow.apply { value = items }
        coEvery { confirmDeletionUseCase.prepare() } returns ConfirmDeletionUseCase.PrepareResult.PermissionRequired(
            setOf("android.permission.READ_MEDIA_IMAGES")
        )

        val viewModel = DeletionConfirmationViewModel(repository, confirmDeletionUseCase)

        advanceUntilIdle()

        val eventDeferred = backgroundScope.async { viewModel.events.first() }

        viewModel.confirmPending()
        advanceUntilIdle()

        val event = eventDeferred.await()
        val permissionEvent = assertIs<DeletionConfirmationEvent.RequestPermission>(event)
        assertTrue(permissionEvent.permissions.contains("android.permission.READ_MEDIA_IMAGES"))
    }
    
    @Test
    fun `confirmPending with no pending emits nothing`() = runTest {
        every { repository.observePending() } returns pendingFlow.apply { value = emptyList() }
        coEvery { confirmDeletionUseCase.prepare() } returns ConfirmDeletionUseCase.PrepareResult.NoPending

        val viewModel = DeletionConfirmationViewModel(repository, confirmDeletionUseCase)

        advanceUntilIdle()

        viewModel.confirmPending()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.inProgress)
    }

    @Test
    fun `handleBatchResult with success continues to next batch or emits FinalSuccess`() = runTest {
        val items = listOf(pendingItem(id = 1L, sizeBytes = 512L))
        every { repository.observePending() } returns pendingFlow.apply { value = items }
        
        val batch = createMockBatch("batch-1", 0)
        coEvery { confirmDeletionUseCase.prepare() } returns ConfirmDeletionUseCase.PrepareResult.Ready(
            batches = listOf(batch),
            initialOutcome = ConfirmDeletionUseCase.Outcome()
        )
        
        val outcome = ConfirmDeletionUseCase.Outcome(confirmedCount = 1, freedBytes = 512L)
        coEvery { confirmDeletionUseCase.handleBatchResult(batch, any(), any()) } returns 
            ConfirmDeletionUseCase.BatchProcessingResult.Completed(outcome)

        val viewModel = DeletionConfirmationViewModel(repository, confirmDeletionUseCase)
        advanceUntilIdle()
        
        viewModel.confirmPending()
        advanceUntilIdle()
        
        val eventDeferred = backgroundScope.async { 
            viewModel.events.first { it is DeletionConfirmationEvent.FinalSuccess }
        }
        
        viewModel.handleBatchResult(batch, android.app.Activity.RESULT_OK, null)
        advanceUntilIdle()
        
        val event = eventDeferred.await()
        val success = assertIs<DeletionConfirmationEvent.FinalSuccess>(event)
        assertEquals(1, success.confirmedCount)
        assertEquals(512L, success.freedBytes)
        assertFalse(viewModel.uiState.value.inProgress)
    }

    private fun pendingItem(id: Long, sizeBytes: Long): DeletionItem {
        return DeletionItem(
            mediaId = id,
            contentUri = "content://media/$id",
            displayName = "photo-$id.jpg",
            sizeBytes = sizeBytes,
            dateTaken = 1_700_000_000_000L + id,
            reason = "manual_confirm",
            createdAt = id,
        )
    }
    
    private fun createMockBatch(id: String, index: Int): ConfirmDeletionUseCase.DeleteBatch {
        return ConfirmDeletionUseCase.DeleteBatch(
            id = id,
            index = index,
            items = listOf(
                ConfirmDeletionUseCase.BatchItem(
                    item = pendingItem(id = 1L, sizeBytes = 512L),
                    uri = Uri.parse("content://media/1"),
                    resolvedSize = 512L
                )
            ),
            intentSender = mockk(),
            requiresRetryAfterApproval = false
        )
    }
}
