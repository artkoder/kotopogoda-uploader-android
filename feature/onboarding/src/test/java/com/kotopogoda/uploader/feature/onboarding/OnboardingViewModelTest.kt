package com.kotopogoda.uploader.feature.onboarding

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.indexer.IndexerRepository
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.logging.test.MainDispatcherRule
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun selectingFolderUpdatesStateWithoutIndexer() = runTest {
        val folderFlow = MutableStateFlow<Folder?>(null)
        val folderRepository = mockk<FolderRepository>()
        every { folderRepository.observeFolder() } returns folderFlow
        coEvery { folderRepository.setFolder(any(), any()) } answers {
            val uri = firstArg<String>()
            val flags = secondArg<Int>()
            folderFlow.value = Folder(
                id = 1,
                treeUri = uri,
                flags = flags,
                lastScanAt = null,
                lastViewedPhotoId = null,
                lastViewedAt = null
            )
        }

        val photoRepository = mockk<PhotoRepository>()
        coEvery { photoRepository.countAll() } returns 4
        coEvery { photoRepository.findIndexAtOrAfter(any()) } returns 0
        coEvery { photoRepository.clampIndex(any()) } answers { firstArg() }

        val reviewProgressStore = mockk<ReviewProgressStore>()
        coEvery { reviewProgressStore.loadPosition(any()) } returns null

        val indexerRepository = mockk<IndexerRepository>()
        every { indexerRepository.isIndexerEnabled } returns false

        val viewModel = OnboardingViewModel(
            folderRepository = folderRepository,
            photoRepository = photoRepository,
            reviewProgressStore = reviewProgressStore,
            indexerRepository = indexerRepository
        )

        viewModel.onFolderSelected("content://test/folder", Intent.FLAG_GRANT_READ_URI_PERMISSION)
        advanceUntilIdle()

        val state = viewModel.uiState.value as OnboardingUiState.FolderSelected
        assertThat(state.photoCount).isEqualTo(4)
        assertThat(state.scanState).isEqualTo(OnboardingScanState.Idle)
    }

    @Test
    fun emitsEventWhenPersistablePermissionGranted() = runTest {
        val folderRepository = mockk<FolderRepository>()
        every { folderRepository.observeFolder() } returns MutableStateFlow<Folder?>(null)

        val photoRepository = mockk<PhotoRepository>()
        coEvery { photoRepository.countAll() } returns 0
        coEvery { photoRepository.findIndexAtOrAfter(any()) } returns 0
        coEvery { photoRepository.clampIndex(any()) } answers { firstArg() }

        val reviewProgressStore = mockk<ReviewProgressStore>()
        coEvery { reviewProgressStore.loadPosition(any()) } returns null

        val indexerRepository = mockk<IndexerRepository>()
        every { indexerRepository.isIndexerEnabled } returns false

        val viewModel = OnboardingViewModel(
            folderRepository = folderRepository,
            photoRepository = photoRepository,
            reviewProgressStore = reviewProgressStore,
            indexerRepository = indexerRepository
        )

        val event = async { viewModel.events.first() }

        viewModel.onPersistablePermissionGranted()

        assertThat(event.await()).isEqualTo(OnboardingEvent.FolderPermissionPersisted)
    }

    @Test
    fun scanTimesOutUpdatesState() = runTest {
        val folderFlow = MutableStateFlow<Folder?>(null)
        val folderRepository = mockk<FolderRepository>()
        every { folderRepository.observeFolder() } returns folderFlow

        val photoRepository = mockk<PhotoRepository>()
        coEvery { photoRepository.countAll() } returns 0
        coEvery { photoRepository.findIndexAtOrAfter(any()) } returns 0
        coEvery { photoRepository.clampIndex(any()) } answers { firstArg() }

        val reviewProgressStore = mockk<ReviewProgressStore>()
        coEvery { reviewProgressStore.loadPosition(any()) } returns null

        val indexerRepository = mockk<IndexerRepository>()
        every { indexerRepository.isIndexerEnabled } returns true
        every { indexerRepository.scanAll() } returns flow {
            awaitCancellation()
        }

        val viewModel = OnboardingViewModel(
            folderRepository = folderRepository,
            photoRepository = photoRepository,
            reviewProgressStore = reviewProgressStore,
            indexerRepository = indexerRepository
        )

        folderFlow.value = Folder(
            id = 1,
            treeUri = "content://test/folder",
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )

        advanceUntilIdle()

        advanceTimeBy(30_000)
        advanceUntilIdle()

        val state = viewModel.uiState.value as OnboardingUiState.FolderSelected
        assertThat(state.scanState).isEqualTo(OnboardingScanState.Timeout)
    }

    @Test
    fun ignoresFolderSelectionWhileScanActive() = runTest {
        val folderFlow = MutableStateFlow<Folder?>(
            Folder(
                id = 1,
                treeUri = "content://test/folder",
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
                lastScanAt = null,
                lastViewedPhotoId = null,
                lastViewedAt = null
            )
        )
        val folderRepository = mockk<FolderRepository>(relaxed = true)
        every { folderRepository.observeFolder() } returns folderFlow

        val photoRepository = mockk<PhotoRepository>()
        coEvery { photoRepository.countAll() } returns 0
        coEvery { photoRepository.findIndexAtOrAfter(any()) } returns 0
        coEvery { photoRepository.clampIndex(any()) } answers { firstArg() }

        val reviewProgressStore = mockk<ReviewProgressStore>()
        coEvery { reviewProgressStore.loadPosition(any()) } returns null

        val indexerRepository = mockk<IndexerRepository>()
        every { indexerRepository.isIndexerEnabled } returns true
        every { indexerRepository.scanAll() } returns flow {
            awaitCancellation()
        }

        val viewModel = OnboardingViewModel(
            folderRepository = folderRepository,
            photoRepository = photoRepository,
            reviewProgressStore = reviewProgressStore,
            indexerRepository = indexerRepository
        )

        runCurrent()

        // Verify scan is active before attempting folder selection
        val stateBefore = viewModel.uiState.value as OnboardingUiState.FolderSelected
        assertThat(stateBefore.scanState).isInstanceOf(OnboardingScanState.InProgress::class.java)

        viewModel.onFolderSelected(
            "content://test/new",
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        runCurrent()

        coVerify(exactly = 0) { folderRepository.setFolder(any(), any()) }
    }
}
