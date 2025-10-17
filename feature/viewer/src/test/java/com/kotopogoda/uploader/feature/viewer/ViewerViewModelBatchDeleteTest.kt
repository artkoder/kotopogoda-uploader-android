package com.kotopogoda.uploader.feature.viewer

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.data.sa.SaFileRepository
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
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

class ViewerViewModelBatchDeleteTest {

    private val testScheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(testScheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        ViewerViewModel.buildVersionOverride = null
    }

    @Test
    fun `finalizeBatchDelete on R+ skips manual deletion and shows success`() =
        runTest(context = dispatcher) {
            ViewerViewModel.buildVersionOverride = Build.VERSION_CODES.R

            mockkStatic(MediaStore::class)

            try {
                val pendingIntent = mockk<PendingIntent>()
                val intentSender = mockk<IntentSender>()
                every { pendingIntent.intentSender } returns intentSender
                every { MediaStore.createDeleteRequest(any(), any()) } returns pendingIntent

                val environment = createEnvironment()
                val photo1 = createPhoto("1")
                val photo2 = createPhoto("2")

                val snackbarDeferred = async {
                    environment.viewModel.events
                        .filterIsInstance<ViewerViewModel.ViewerEvent.ShowSnackbar>()
                        .first()
                }

                environment.viewModel.onToggleSelection(photo1)
                environment.viewModel.onToggleSelection(photo2)
                environment.viewModel.onDeleteSelection()
                advanceUntilIdle()

                environment.viewModel.onDeleteResult(ViewerViewModel.DeleteResult.Success)
                advanceUntilIdle()

                val snackbar = snackbarDeferred.await()
                assertEquals(R.string.viewer_snackbar_delete_success, snackbar.messageRes)
                verify(exactly = 0) { environment.resolver.delete(any(), any(), any()) }
            } finally {
                unmockkStatic(MediaStore::class)
            }
        }

    @Test
    fun `finalizeBatchDelete on pre-R deletes manually and shows success`() =
        runTest(context = dispatcher) {
            ViewerViewModel.buildVersionOverride = Build.VERSION_CODES.Q

            val environment = createEnvironment()
            val photo1 = createPhoto("1")
            val photo2 = createPhoto("2")

            every { environment.resolver.delete(any(), any(), any()) } returnsMany listOf(1, 1)

            val snackbarDeferred = async {
                environment.viewModel.events
                    .filterIsInstance<ViewerViewModel.ViewerEvent.ShowSnackbar>()
                    .first()
            }

            environment.viewModel.onToggleSelection(photo1)
            environment.viewModel.onToggleSelection(photo2)
            environment.viewModel.onDeleteSelection()
            advanceUntilIdle()

            val snackbar = snackbarDeferred.await()
            assertEquals(R.string.viewer_snackbar_delete_success, snackbar.messageRes)
            verify(exactly = 2) { environment.resolver.delete(any(), any(), any()) }
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
        every { uploadQueueRepository.observeQueue() } returns flowOf(emptyList())
        every { uploadQueueRepository.observeQueuedOrProcessing(any()) } returns flowOf(false)
        every { uploadEnqueuer.isEnqueued(any()) } returns flowOf(false)
        coEvery { folderRepository.getFolder() } returns null
        coEvery { reviewProgressStore.loadPosition(any()) } returns null
        coEvery { reviewProgressStore.savePosition(any(), any(), any()) } just Runs

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
            reviewProgressStore = reviewProgressStore,
            context = context,
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
