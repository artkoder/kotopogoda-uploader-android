package com.kotopogoda.uploader.feature.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.data.sa.SaFileRepository
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.logging.test.MainDispatcherRule
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.AppSettings
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.core.settings.PreviewQuality
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceAdapter
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewerViewModelJumpToDateTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `jumpToDate scrolls to first photo even when date taken missing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
        val environment = createEnvironment()
        advanceUntilIdle()

        val target = Instant.parse("2025-01-02T10:15:30Z")
        val zone = ZoneId.systemDefault()
        val localDate = target.atZone(zone).toLocalDate()
        val startOfDay = localDate.atStartOfDay(zone).toInstant()
        val endOfDay = localDate.plusDays(1).atStartOfDay(zone).toInstant()

        coEvery { environment.photoRepository.findIndexAtOrAfter(startOfDay, endOfDay) } returns 7

        environment.viewModel.jumpToDate(target)
        advanceUntilIdle()

        assertEquals(7, environment.viewModel.currentIndex.value)
        coVerify(exactly = 1) {
            environment.photoRepository.findIndexAtOrAfter(startOfDay, endOfDay)
        }
    }

    @Test
    fun `jumpToDate emits event when day is empty`() = runTest(context = mainDispatcherRule.dispatcher) {
        val environment = createEnvironment()
        advanceUntilIdle()

        val target = Instant.parse("2025-01-02T10:15:30Z")
        val zone = ZoneId.systemDefault()
        val localDate = target.atZone(zone).toLocalDate()
        val startOfDay = localDate.atStartOfDay(zone).toInstant()
        val endOfDay = localDate.plusDays(1).atStartOfDay(zone).toInstant()

        coEvery { environment.photoRepository.findIndexAtOrAfter(startOfDay, endOfDay) } returns null
        val initialIndex = environment.viewModel.currentIndex.value
        val eventDeferred = async { environment.viewModel.events.first() }

        environment.viewModel.jumpToDate(target)
        advanceUntilIdle()

        val event = eventDeferred.await()

        assertEquals(initialIndex, environment.viewModel.currentIndex.value)
        assertTrue(event is ViewerViewModel.ViewerEvent.ShowToast)
        val toast = event as ViewerViewModel.ViewerEvent.ShowToast
        assertEquals(R.string.viewer_toast_no_photos_for_day, toast.messageRes)
    }

    private fun createEnvironment(): ViewModelEnvironment {
        val photoRepository = mockk<PhotoRepository>()
        val folderRepository = mockk<FolderRepository>()
        val saFileRepository = mockk<SaFileRepository>()
        val uploadEnqueuer = mockk<UploadEnqueuer>()
        val uploadQueueRepository = mockk<UploadQueueRepository>()
        val nativeEnhanceAdapter = mockk<NativeEnhanceAdapter>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()
        val reviewProgressStore = mockk<ReviewProgressStore>()
        val savedStateHandle = SavedStateHandle()

        every { photoRepository.observePhotos() } returns flowOf(PagingData.empty())
        every { folderRepository.observeFolder() } returns flowOf(null)
        coEvery { folderRepository.getFolder() } returns null
        coEvery { reviewProgressStore.loadPosition(any()) } returns null
        coEvery { reviewProgressStore.savePosition(any(), any(), any()) } just Runs
        every { uploadQueueRepository.observeQueue() } returns flowOf(emptyList())
        every { uploadQueueRepository.observeQueuedOrProcessing(any<Uri>()) } returns flowOf(false)
        every { uploadQueueRepository.observeQueuedOrProcessing(any<String>()) } returns flowOf(false)
        every { uploadEnqueuer.isEnqueued(any()) } returns flowOf(false)
        every { settingsRepository.flow } returns flowOf(
            AppSettings(
                baseUrl = "https://example.com",
                appLogging = true,
                httpLogging = true,
                persistentQueueNotification = false,
                previewQuality = PreviewQuality.BALANCED,
            )
        )
        every { nativeEnhanceAdapter.isReady() } returns false
        coEvery { nativeEnhanceAdapter.initialize(any()) } returns Unit

        val context = mockk<Context>(relaxed = true)

        val viewModel = ViewerViewModel(
            photoRepository = photoRepository,
            folderRepository = folderRepository,
            saFileRepository = saFileRepository,
            uploadEnqueuer = uploadEnqueuer,
            uploadQueueRepository = uploadQueueRepository,
            reviewProgressStore = reviewProgressStore,
            context = context,
            nativeEnhanceAdapter = nativeEnhanceAdapter,
            settingsRepository = settingsRepository,
            savedStateHandle = savedStateHandle
        )

        return ViewModelEnvironment(viewModel, photoRepository)
    }

    private data class ViewModelEnvironment(
        val viewModel: ViewerViewModel,
        val photoRepository: PhotoRepository
    )
}
