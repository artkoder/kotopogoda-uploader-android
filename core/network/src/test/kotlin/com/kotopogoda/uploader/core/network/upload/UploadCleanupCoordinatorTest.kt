package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import com.kotopogoda.uploader.core.data.deletion.DeletionQueueRepository
import com.kotopogoda.uploader.core.data.deletion.DeletionRequest
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.data.upload.UploadSuccessListener
import com.kotopogoda.uploader.core.data.upload.UploadSourceInfo
import com.kotopogoda.uploader.core.settings.AppSettings
import com.kotopogoda.uploader.core.settings.PreviewQuality
import com.kotopogoda.uploader.core.settings.SettingsRepository
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.match
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UploadCleanupCoordinatorTest {

    @Test
    fun `onUploadSucceeded enqueues deletion when setting enabled`() = runTest {
        val settingsRepository = mockk<SettingsRepository> {
            every { flow } returns flowOf(appSettings(autoDeleteAfterUpload = true))
        }
        val deletionQueueRepository = mockk<DeletionQueueRepository>(relaxed = true)
        val uploadQueueRepository = mockk<UploadQueueRepository>()
        val coordinator = UploadCleanupCoordinator(
            settingsRepository = settingsRepository,
            deletionQueueRepository = deletionQueueRepository,
            uploadQueueRepository = immediateLazy(uploadQueueRepository),
        )
        val contentUri = Uri.parse("content://media/external/images/media/123")

        coEvery { uploadQueueRepository.findSourceForItem(10L) } returns UploadSourceInfo(
            photoId = "photo-10",
            uri = contentUri,
            sizeBytes = 4096L,
        )
        coEvery { deletionQueueRepository.enqueue(any()) } returns 1

        coordinator.onUploadSucceeded(
            itemId = 10L,
            photoId = "photo-10",
            contentUri = contentUri,
            displayName = "IMG_0010.jpg",
            sizeBytes = 4096L,
            trigger = UploadSuccessListener.TRIGGER_ACCEPTED,
            uploadId = "upload-123",
        )

        coVerify(exactly = 1) {
            deletionQueueRepository.enqueue(match { requests ->
                requests.size == 1 &&
                    requests.single().matchesRequest(
                        mediaId = 123L,
                        uri = contentUri.toString(),
                        sizeBytes = 4096L,
                        displayName = "IMG_0010.jpg",
                    )
            })
        }
    }

    @Test
    fun `handleUploadSuccess skips when auto delete disabled`() = runTest {
        val settingsRepository = mockk<SettingsRepository> {
            every { flow } returns flowOf(appSettings(autoDeleteAfterUpload = false))
        }
        val deletionQueueRepository = mockk<DeletionQueueRepository>(relaxed = true)
        val uploadQueueRepository = mockk<UploadQueueRepository>()
        val coordinator = UploadCleanupCoordinator(
            settingsRepository = settingsRepository,
            deletionQueueRepository = deletionQueueRepository,
            uploadQueueRepository = immediateLazy(uploadQueueRepository),
        )

        val result = coordinator.handleUploadSuccess(
            itemId = 20L,
            uploadUri = Uri.parse("content://media/external/images/media/200"),
            displayName = "IMG_0020.jpg",
            reportedSizeBytes = 1024L,
            httpCode = 200,
            successKind = "test",
        )

        assertTrue(result is UploadCleanupCoordinator.CleanupResult.Skipped)
        assertEquals(UploadCleanupCoordinator.SkipReason.SETTINGS_DISABLED, result.reason)
        coVerify(exactly = 0) { deletionQueueRepository.enqueue(any()) }
    }

    @Test
    fun `onUploadSucceeded is idempotent for repeated signals`() = runTest {
        val settingsRepository = mockk<SettingsRepository> {
            every { flow } returns flowOf(appSettings(autoDeleteAfterUpload = true))
        }
        val deletionQueueRepository = mockk<DeletionQueueRepository>(relaxed = true)
        val uploadQueueRepository = mockk<UploadQueueRepository>()
        val coordinator = UploadCleanupCoordinator(
            settingsRepository = settingsRepository,
            deletionQueueRepository = deletionQueueRepository,
            uploadQueueRepository = immediateLazy(uploadQueueRepository),
        )
        val contentUri = Uri.parse("content://media/external/images/media/555")

        coEvery { uploadQueueRepository.findSourceForItem(55L) } returns UploadSourceInfo(
            photoId = "photo-55",
            uri = contentUri,
            sizeBytes = 2048L,
        )
        coEvery { deletionQueueRepository.enqueue(any()) } returns 1

        coordinator.onUploadSucceeded(
            itemId = 55L,
            photoId = "photo-55",
            contentUri = contentUri,
            displayName = "IMG_0055.jpg",
            sizeBytes = 2048L,
            trigger = UploadSuccessListener.TRIGGER_SUCCEEDED,
            uploadId = null,
        )
        coordinator.onUploadSucceeded(
            itemId = 55L,
            photoId = "photo-55",
            contentUri = contentUri,
            displayName = "IMG_0055.jpg",
            sizeBytes = 2048L,
            trigger = UploadSuccessListener.TRIGGER_SUCCEEDED,
            uploadId = null,
        )

        coVerify(exactly = 1) { deletionQueueRepository.enqueue(any()) }
        coVerify(exactly = 1) { uploadQueueRepository.findSourceForItem(55L) }
    }

    private fun appSettings(autoDeleteAfterUpload: Boolean): AppSettings {
        return AppSettings(
            baseUrl = "https://example.com",
            appLogging = false,
            httpLogging = false,
            persistentQueueNotification = false,
            previewQuality = PreviewQuality.BALANCED,
            autoDeleteAfterUpload = autoDeleteAfterUpload,
            forceCpuForEnhancement = false,
        )
    }

    private fun immediateLazy(repository: UploadQueueRepository): Lazy<UploadQueueRepository> {
        return object : Lazy<UploadQueueRepository> {
            override fun get(): UploadQueueRepository = repository
        }
    }

    private fun DeletionRequest.matchesRequest(
        mediaId: Long,
        uri: String,
        sizeBytes: Long?,
        displayName: String,
    ): Boolean {
        return this.mediaId == mediaId &&
            this.contentUri == uri &&
            this.displayName == displayName &&
            this.reason == "uploaded_cleanup" &&
            this.sizeBytes == sizeBytes
    }
}
