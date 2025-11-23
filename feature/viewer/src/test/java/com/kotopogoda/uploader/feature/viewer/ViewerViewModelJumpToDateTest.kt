package com.kotopogoda.uploader.feature.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.kotopogoda.uploader.core.data.deletion.DeletionQueueRepository
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoItem
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
    fun `jumpToDate scrolls to requested date in descending order`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val environment = createEnvironment()
            advanceUntilIdle()

            val photos = listOf(
                photo(id = 0, instant = "2025-01-05T00:00:00Z"),
                photo(id = 1, instant = "2025-01-04T00:00:00Z"),
                photo(id = 2, instant = "2025-01-03T00:00:00Z"),
                photo(id = 3, instant = "2025-01-02T00:00:00Z"),
            )
            stubPhotos(environment.photoRepository, photos)
            environment.viewModel.updateVisiblePhoto(photos.size, photos.first())

            val target = Instant.parse("2025-01-03T10:15:30Z")

            environment.viewModel.jumpToDate(target)
            advanceUntilIdle()

            assertEquals(2, environment.viewModel.currentIndex.value)
            coVerify(atLeast = 1) {
                environment.photoRepository.getPhotoAt(any())
            }
        }

    @Test
    fun `jumpToDate emits event when no earlier photos exist`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val environment = createEnvironment()
            advanceUntilIdle()

            val photos = listOf(
                photo(id = 0, instant = "2025-01-10T00:00:00Z"),
                photo(id = 1, instant = "2025-01-09T00:00:00Z"),
            )
            stubPhotos(environment.photoRepository, photos)
            environment.viewModel.updateVisiblePhoto(photos.size, photos.first())

            val target = Instant.parse("2024-01-02T00:00:00Z")
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

    @Test
    fun `jumpToDate skips jump when result is too far`() = runTest(context = mainDispatcherRule.dispatcher) {
        val environment = createEnvironment()
        advanceUntilIdle()

        val photos = listOf(
            photo(id = 0, instant = "2024-01-15T00:00:00Z"),
            photo(id = 1, instant = "2024-01-10T00:00:00Z"),
            photo(id = 2, instant = "2023-12-20T00:00:00Z"),
            photo(id = 3, instant = "2023-12-10T00:00:00Z"),
        )
        stubPhotos(environment.photoRepository, photos)
        environment.viewModel.updateVisiblePhoto(photos.size, photos.first())

        val target = Instant.parse("2024-01-01T00:00:00Z")
        val initialIndex = environment.viewModel.currentIndex.value
        val eventDeferred = async { environment.viewModel.events.first() }

        environment.viewModel.jumpToDate(target)
        advanceUntilIdle()

        val event = eventDeferred.await()

        assertEquals(initialIndex, environment.viewModel.currentIndex.value)
        assertTrue(event is ViewerViewModel.ViewerEvent.ShowToast)
    }

    @Test
    fun `jumpToDate handles ascending order by falling back to closest earlier date`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val environment = createEnvironment()
            advanceUntilIdle()

            val photos = listOf(
                photo(id = 0, instant = "2022-01-01T00:00:00Z"),
                photo(id = 1, instant = "2022-01-05T00:00:00Z"),
                photo(id = 2, instant = "2022-02-01T00:00:00Z"),
                photo(id = 3, instant = "2022-03-01T00:00:00Z"),
            )
            stubPhotos(environment.photoRepository, photos)
            environment.viewModel.updateVisiblePhoto(photos.size, photos.first())

            val target = Instant.parse("2022-01-20T00:00:00Z")

            environment.viewModel.jumpToDate(target)
            advanceUntilIdle()

            assertEquals(1, environment.viewModel.currentIndex.value)
        }

    private fun createEnvironment(): ViewModelEnvironment {
        val photoRepository = mockk<PhotoRepository>()
        val folderRepository = mockk<FolderRepository>()
        val saFileRepository = mockk<SaFileRepository>()
        val uploadEnqueuer = mockk<UploadEnqueuer>()
        val uploadQueueRepository = mockk<UploadQueueRepository>()
        val deletionQueueRepository = mockk<DeletionQueueRepository>()
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
        every { deletionQueueRepository.observePending() } returns flowOf(emptyList())
        coEvery { deletionQueueRepository.getPending() } returns emptyList()
        coEvery { deletionQueueRepository.enqueue(any()) } returns 0
        coEvery { deletionQueueRepository.markSkipped(any()) } returns 0
        every { settingsRepository.flow } returns flowOf(
            AppSettings(
                baseUrl = "https://example.com",
                appLogging = true,
                httpLogging = true,
                persistentQueueNotification = false,
                previewQuality = PreviewQuality.BALANCED,
                autoDeleteAfterUpload = false,
                forceCpuForEnhancement = false,
            )
        )
        every { nativeEnhanceAdapter.isReady() } returns false
        coEvery { nativeEnhanceAdapter.initialize(any()) } returns Unit
        coEvery { photoRepository.getPhotoAt(any()) } returns null

        val context = mockk<Context>(relaxed = true)

        val viewModel = ViewerViewModel(
            photoRepository = photoRepository,
            folderRepository = folderRepository,
            saFileRepository = saFileRepository,
            uploadEnqueuer = uploadEnqueuer,
            uploadQueueRepository = uploadQueueRepository,
            deletionQueueRepository = deletionQueueRepository,
            reviewProgressStore = reviewProgressStore,
            context = context,
            nativeEnhanceAdapter = nativeEnhanceAdapter,
            settingsRepository = settingsRepository,
            savedStateHandle = savedStateHandle
        )

        return ViewModelEnvironment(viewModel, photoRepository)
    }

    private fun stubPhotos(photoRepository: PhotoRepository, photos: List<PhotoItem>) {
        coEvery { photoRepository.getPhotoAt(any()) } coAnswers {
            val index = arg<Int>(0)
            photos.getOrNull(index)
        }
    }

    private fun photo(id: Int, instant: String): PhotoItem = PhotoItem(
        id = id.toString(),
        uri = Uri.parse("content://test/photo/$id"),
        takenAt = Instant.parse(instant)
    )

    private data class ViewModelEnvironment(
        val viewModel: ViewerViewModel,
        val photoRepository: PhotoRepository
    )
}
