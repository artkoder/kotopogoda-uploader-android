package com.kotopogoda.uploader.feature.viewer

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.data.sa.SaFileRepository
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewerViewModelJumpToDateTest {

    private val testScheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(testScheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `jumpToDate scrolls to first photo even when date taken missing`() =
        runTest(context = dispatcher) {
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
    fun `jumpToDate emits event when day is empty`() = runTest(context = dispatcher) {
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
        val reviewProgressStore = mockk<ReviewProgressStore>()
        val savedStateHandle = SavedStateHandle()

        every { photoRepository.observePhotos() } returns flowOf(PagingData.empty())
        every { folderRepository.observeFolder() } returns flowOf(null)
        coEvery { folderRepository.getFolder() } returns null
        coEvery { reviewProgressStore.loadPosition(any()) } returns null
        coEvery { reviewProgressStore.savePosition(any(), any(), any()) } just Runs
        every { uploadQueueRepository.observeQueue() } returns flowOf(emptyList())
        every { uploadQueueRepository.observeQueuedOrProcessing(any()) } returns flowOf(false)
        every { uploadEnqueuer.isEnqueued(any()) } returns flowOf(false)

        val context = mockk<Context>(relaxed = true)

        val viewModel = ViewerViewModel(
            photoRepository = photoRepository,
            folderRepository = folderRepository,
            saFileRepository = saFileRepository,
            uploadEnqueuer = uploadEnqueuer,
            uploadQueueRepository = uploadQueueRepository,
            reviewProgressStore = reviewProgressStore,
            context = context,
            savedStateHandle = savedStateHandle
        )

        return ViewModelEnvironment(viewModel, photoRepository)
    }

    private data class ViewModelEnvironment(
        val viewModel: ViewerViewModel,
        val photoRepository: PhotoRepository
    )
}
