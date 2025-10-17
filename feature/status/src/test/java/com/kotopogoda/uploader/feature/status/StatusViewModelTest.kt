package com.kotopogoda.uploader.feature.status

import android.content.ContentResolver
import android.content.Context
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.health.HealthMonitor
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.network.health.HealthStatus
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class StatusViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val healthMonitor: HealthMonitor = mockk()
    private val deviceCredsStore: DeviceCredsStore = mockk()
    private val uploadQueueRepository: UploadQueueRepository = mockk()
    private val folderRepository: FolderRepository = mockk()
    private val context: Context = mockk()
    private val contentResolver: ContentResolver = mockk(relaxed = true)

    @Before
    fun setup() {
        every { healthMonitor.state } returns MutableStateFlow(HealthState.Unknown)
        every { healthMonitor.start(any(), any()) } just Runs
        every { deviceCredsStore.credsFlow } returns MutableStateFlow(null)
        every { uploadQueueRepository.observeQueue() } returns flowOf(emptyList())
        every { folderRepository.observeFolder() } returns flowOf(null)
        every { context.contentResolver } returns contentResolver
    }

    @Test
    fun `onRefreshHealth emits success event with latency`() = runTest {
        val latency = 321L
        val healthState = HealthState(
            status = HealthStatus.ONLINE,
            lastCheckedAt = Instant.now(),
            message = null,
            latencyMillis = latency,
        )
        coEvery { healthMonitor.checkOnce() } returns healthState

        val viewModel = createViewModel()

        val eventDeferred = async {
            viewModel.events.first { it is StatusEvent.HealthPingResult }
        }

        viewModel.onRefreshHealth()
        advanceUntilIdle()

        val event = eventDeferred.await() as StatusEvent.HealthPingResult
        assertThat(event.isSuccess).isTrue()
        assertThat(event.latencyMillis).isEqualTo(latency)
        assertThat(event.error).isNull()
    }

    @Test
    fun `onRefreshHealth emits error event when check fails`() = runTest {
        val error = IllegalStateException("boom")
        coEvery { healthMonitor.checkOnce() } throws error

        val viewModel = createViewModel()

        val eventDeferred = async {
            viewModel.events.first { it is StatusEvent.HealthPingResult }
        }

        viewModel.onRefreshHealth()
        advanceUntilIdle()

        val event = eventDeferred.await() as StatusEvent.HealthPingResult
        assertThat(event.isSuccess).isFalse()
        assertThat(event.latencyMillis).isNull()
        assertThat(event.error).isEqualTo(error)
    }

    private fun createViewModel(): StatusViewModel {
        return StatusViewModel(
            healthMonitor = healthMonitor,
            deviceCredsStore = deviceCredsStore,
            uploadQueueRepository = uploadQueueRepository,
            folderRepository = folderRepository,
            context = context,
        )
    }
}
