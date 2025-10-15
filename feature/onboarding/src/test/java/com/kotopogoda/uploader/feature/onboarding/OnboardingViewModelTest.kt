package com.kotopogoda.uploader.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.indexer.IndexerRepository
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun selectingFolderUpdatesStateWithoutIndexer() = runTest {
        val folderFlow = MutableStateFlow<Folder?>(null)
        val folderRepository = mockk<FolderRepository>()
        every { folderRepository.observeFolder() } returns folderFlow
        coEvery { folderRepository.setFolder(any()) } coAnswers {
            val uri = firstArg<String>()
            folderFlow.value = Folder(
                id = 1,
                treeUri = uri,
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

        viewModel.onFolderSelected("content://test/folder")
        advanceUntilIdle()

        val state = viewModel.uiState.value as OnboardingUiState.FolderSelected
        assertThat(state.photoCount).isEqualTo(4)
        assertThat(state.scanState).isEqualTo(OnboardingScanState.Idle)
    }
}
