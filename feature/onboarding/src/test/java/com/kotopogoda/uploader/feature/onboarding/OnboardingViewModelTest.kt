package com.kotopogoda.uploader.feature.onboarding

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.indexer.IndexerRepository
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import io.mockk.Runs
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    @Test
    fun selectingFolderTriggersScanAndPopulatesPhotos() = runTest {
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

        val photosFlow = MutableStateFlow<List<PhotoItem>>(emptyList())
        val photoRepository = mockk<PhotoRepository>()
        every { photoRepository.observePhotos() } returns photosFlow
        coEvery { photoRepository.countAll() } coAnswers { photosFlow.value.size }
        coEvery { photoRepository.findIndexAtOrAfter(any()) } returns 0

        val reviewProgressStore = mockk<ReviewProgressStore>()
        coEvery { reviewProgressStore.loadPosition(any()) } returns null
        coEvery { reviewProgressStore.clear(any()) } just Runs
        coEvery { reviewProgressStore.savePosition(any(), any(), any()) } just Runs

        val indexerRepository = mockk<IndexerRepository>()
        every { indexerRepository.scanAll() } returns flow {
            emit(IndexerRepository.ScanProgress())
            emit(IndexerRepository.ScanProgress(scanned = 1, inserted = 1))
            photosFlow.value = listOf(
                PhotoItem(
                    id = "photo",
                    uri = Uri.parse("content://test/photo"),
                    takenAt = null
                )
            )
        }

        val viewModel = OnboardingViewModel(
            folderRepository = folderRepository,
            photoRepository = photoRepository,
            reviewProgressStore = reviewProgressStore,
            indexerRepository = indexerRepository
        )

        viewModel.onFolderSelected("content://test/folder")
        advanceUntilIdle()

        assertThat(photosFlow.value).isNotEmpty()
        val state = viewModel.uiState.value as OnboardingUiState.FolderSelected
        assertThat(state.photoCount).isEqualTo(photosFlow.value.size)
        assertThat(state.scanState).isInstanceOf(OnboardingScanState.Completed::class.java)
        val completed = state.scanState as OnboardingScanState.Completed
        assertThat(completed.progress?.inserted).isEqualTo(1)
    }
}
