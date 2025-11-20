package com.kotopogoda.uploader.feature.viewer

import android.content.ContentResolver
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
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.AppSettings
import com.kotopogoda.uploader.core.settings.PreviewQuality
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceAdapter
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ViewerViewModelSkipTest {

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
    fun `onSkip should NOT emit ShowSnackbar`() = runTest(context = dispatcher) {
        val environment = createEnvironment()
        val photo = createPhoto("1")

        // Initial setup
        environment.viewModel.updateVisiblePhoto(10, photo)

        val events = mutableListOf<ViewerViewModel.ViewerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            environment.viewModel.events.collect { events.add(it) }
        }

        environment.viewModel.onSkip(photo)
        advanceUntilIdle()

        // We expect NO ShowSnackbar events
        val snackbarEvents = events.filterIsInstance<ViewerViewModel.ViewerEvent.ShowSnackbar>()
        assertTrue(snackbarEvents.isEmpty(), "Expected no ShowSnackbar events, but found: $snackbarEvents")
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
        every { uploadQueueRepository.observeQueue() } returns flowOf(emptyList())
        every { uploadQueueRepository.observeQueuedOrProcessing(any<Uri>()) } returns flowOf(false)
        every { uploadQueueRepository.observeQueuedOrProcessing(any<String>()) } returns flowOf(false)
        every { uploadEnqueuer.isEnqueued(any()) } returns flowOf(false)
        every { deletionQueueRepository.observePending() } returns flowOf(emptyList())
        coEvery { deletionQueueRepository.getPending() } returns emptyList()
        coEvery { deletionQueueRepository.enqueue(any()) } returns 0
        coEvery { deletionQueueRepository.markSkipped(any()) } returns 0
        coEvery { folderRepository.getFolder() } returns null
        coEvery { reviewProgressStore.loadPosition(any()) } returns null
        coEvery { reviewProgressStore.savePosition(any(), any(), any()) } just Runs
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

        val resolver = mockk<ContentResolver>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.contentResolver } returns resolver
        every { context.cacheDir } returns createTempDir(prefix = "viewer-test")

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

        return ViewModelEnvironment(viewModel, resolver)
    }

    private fun createPhoto(id: String): PhotoItem =
        PhotoItem(
            id = id,
            uri = Uri.parse("content://test/$id"),
            takenAt = Instant.now()
        )

    private data class ViewModelEnvironment(
        val viewModel: ViewerViewModel,
        val resolver: ContentResolver
    )
}
