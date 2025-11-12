package com.kotopogoda.uploader.core.data.deletion

import com.kotopogoda.uploader.core.logging.test.MainDispatcherRule
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
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

    private val pendingFlow = MutableStateFlow<List<DeletionItem>>(emptyList())

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        every { repository.observePending() } returns pendingFlow
    }

    @Test
    fun `uiState reflects pending queue`() = runTest {
        val viewModel = DeletionConfirmationViewModel(repository)

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
    fun `confirmPending emits success event and resets progress`() = runTest {
        val items = listOf(
            pendingItem(id = 4L, sizeBytes = 512L),
            pendingItem(id = 5L, sizeBytes = 256L),
        )
        every { repository.observePending() } returns pendingFlow.apply { value = items }
        coEvery { repository.markConfirmed(items.map { it.mediaId }) } coAnswers {
            pendingFlow.value = emptyList()
            items.size
        }

        val viewModel = DeletionConfirmationViewModel(repository)

        advanceUntilIdle()

        val eventDeferred = backgroundScope.async { viewModel.events.first() }

        viewModel.confirmPending()

        assertTrue(viewModel.uiState.value.inProgress)

        advanceUntilIdle()

        val event = eventDeferred.await()
        val success = assertIs<DeletionConfirmationEvent.ConfirmationSuccess>(event)
        assertEquals(items.size, success.confirmedCount)
        assertEquals(768L, success.totalBytes)
        assertFalse(viewModel.uiState.value.inProgress)
        assertFalse(viewModel.uiState.value.isConfirmEnabled)
    }

    @Test
    fun `confirmPending emits failure on exception`() = runTest {
        val items = listOf(pendingItem(id = 10L, sizeBytes = 1_000L))
        every { repository.observePending() } returns pendingFlow.apply { value = items }
        coEvery { repository.markConfirmed(items.map { it.mediaId }) } throws IllegalStateException("boom")

        val viewModel = DeletionConfirmationViewModel(repository)

        advanceUntilIdle()

        val eventDeferred = backgroundScope.async { viewModel.events.first() }

        viewModel.confirmPending()
        advanceUntilIdle()

        val event = eventDeferred.await()
        assertIs<DeletionConfirmationEvent.ConfirmationFailed>(event)
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
}
