package com.kotopogoda.uploader.core.network.upload

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.data.deletion.DeletionQueueRepository
import com.kotopogoda.uploader.core.data.deletion.DeletionRequest
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueItem
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.data.upload.UploadSourceInfo
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.UploadTaskResult
import com.kotopogoda.uploader.core.settings.AppSettings
import com.kotopogoda.uploader.core.settings.PreviewQuality
import com.kotopogoda.uploader.core.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.kotopogoda.uploader.core.work.WorkManagerProvider

@RunWith(RobolectricTestRunner::class)
class UploadProcessorWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `worker recovers stuck processing before fetching batch`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val deletionQueueRepository = mockk<DeletionQueueRepository>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()
        val workManager = mockk<WorkManager>()
        val workManagerProvider = WorkManagerProvider { workManager }
        val constraintsHelper = mockk<UploadConstraintsHelper>()
        val taskRunner = mockk<UploadTaskRunner>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 1L,
            uri = Uri.parse("content://example/test/123"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )

        val appSettings = AppSettings(
            baseUrl = "https://example.com",
            appLogging = true,
            httpLogging = true,
            persistentQueueNotification = false,
            previewQuality = PreviewQuality.BALANCED,
            autoDeleteAfterUpload = false,
        )

        every { settingsRepository.flow } returns flowOf(appSettings)
        coEvery { repository.recoverStuckProcessing() } returns 1
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.getState(queueItem.id) } returns UploadItemState.PROCESSING
        coEvery { repository.markSucceeded(queueItem.id) } returns Unit
        coEvery { repository.hasQueued() } returns false
        coEvery { taskRunner.run(any()) } returns UploadTaskResult.Success(
            completionState = UploadTaskRunner.DeleteCompletionState.DELETED,
            bytesSent = 100L,
            totalBytes = 100L,
        )
        every { constraintsHelper.buildConstraints() } returns Constraints.NONE
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk<Operation>(relaxed = true)

        val worker = UploadProcessorWorker(
            context,
            workerParams,
            repository,
            deletionQueueRepository,
            settingsRepository,
            workManagerProvider,
            constraintsHelper,
            taskRunner,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerifyOrder {
            repository.recoverStuckProcessing()
            repository.fetchQueued(any(), recoverStuck = false)
        }
        coVerify { repository.fetchQueued(match { it == 5 }, recoverStuck = false) }
        coVerify { repository.markProcessing(queueItem.id) }
        coVerify { repository.getState(queueItem.id) }
        coVerify { repository.markSucceeded(queueItem.id) }
        coVerify { deletionQueueRepository.markUploading(listOf(123L), true) }
        coVerify { deletionQueueRepository.markUploading(listOf(123L), false) }
        coVerify(exactly = 0) { deletionQueueRepository.enqueue(any()) }
    }

    @Test
    fun `worker skips state updates when item no longer processing`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val deletionQueueRepository = mockk<DeletionQueueRepository>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()
        val workManager = mockk<WorkManager>()
        val workManagerProvider = WorkManagerProvider { workManager }
        val constraintsHelper = mockk<UploadConstraintsHelper>()
        val taskRunner = mockk<UploadTaskRunner>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 2L,
            uri = Uri.parse("content://example/skip/456"),
            idempotencyKey = "idempotency", 
            displayName = "photo.jpg",
            size = 10L,
        )

        val appSettings = AppSettings(
            baseUrl = "https://example.com",
            appLogging = true,
            httpLogging = true,
            persistentQueueNotification = false,
            previewQuality = PreviewQuality.BALANCED,
            autoDeleteAfterUpload = false,
        )

        every { settingsRepository.flow } returns flowOf(appSettings)
        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.getState(queueItem.id) } returns UploadItemState.FAILED
        coEvery { repository.hasQueued() } returns false
        coEvery { taskRunner.run(any()) } returns UploadTaskResult.Success(
            completionState = UploadTaskRunner.DeleteCompletionState.DELETED,
            bytesSent = 100L,
            totalBytes = 100L,
        )
        every { constraintsHelper.buildConstraints() } returns Constraints.NONE
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)

        val worker = UploadProcessorWorker(
            context,
            workerParams,
            repository,
            deletionQueueRepository,
            settingsRepository,
            workManagerProvider,
            constraintsHelper,
            taskRunner,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { repository.getState(queueItem.id) }
        coVerify(exactly = 0) { repository.markSucceeded(queueItem.id) }
        coVerify(exactly = 0) { repository.markFailed(any(), any(), any(), any(), any()) }
        coVerify { deletionQueueRepository.markUploading(listOf(456L), true) }
        coVerify { deletionQueueRepository.markUploading(listOf(456L), false) }
    }

    @Test
    fun `worker skips upload when item cannot transition to processing`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val deletionQueueRepository = mockk<DeletionQueueRepository>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()
        val workManager = mockk<WorkManager>()
        val workManagerProvider = WorkManagerProvider { workManager }
        val constraintsHelper = mockk<UploadConstraintsHelper>()
        val taskRunner = mockk<UploadTaskRunner>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 3L,
            uri = Uri.parse("content://example/cancelled/789"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )

        val appSettings = AppSettings(
            baseUrl = "https://example.com",
            appLogging = true,
            httpLogging = true,
            persistentQueueNotification = false,
            previewQuality = PreviewQuality.BALANCED,
            autoDeleteAfterUpload = false,
        )

        every { settingsRepository.flow } returns flowOf(appSettings)
        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns false
        coEvery { repository.hasQueued() } returns false
        every { constraintsHelper.buildConstraints() } returns Constraints.NONE
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)

        val worker = UploadProcessorWorker(
            context,
            workerParams,
            repository,
            deletionQueueRepository,
            settingsRepository,
            workManagerProvider,
            constraintsHelper,
            taskRunner,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { repository.markProcessing(queueItem.id) }
        coVerify(exactly = 0) { taskRunner.run(any()) }
        coVerify { deletionQueueRepository.markUploading(listOf(789L), true) }
        coVerify { deletionQueueRepository.markUploading(listOf(789L), false) }
    }

    @Test
    fun `worker enqueues deletion when autoDeleteAfterUpload is enabled and upload succeeds`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val deletionQueueRepository = mockk<DeletionQueueRepository>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()
        val workManager = mockk<WorkManager>()
        val workManagerProvider = WorkManagerProvider { workManager }
        val constraintsHelper = mockk<UploadConstraintsHelper>()
        val taskRunner = mockk<UploadTaskRunner>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 4L,
            uri = Uri.parse("content://example/auto_delete/999"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 12345L,
        )
        val sourceInfo = UploadSourceInfo(
            photoId = "photo123",
            uri = Uri.parse("content://example/auto_delete/999"),
        )

        val appSettings = AppSettings(
            baseUrl = "https://example.com",
            appLogging = true,
            httpLogging = true,
            persistentQueueNotification = false,
            previewQuality = PreviewQuality.BALANCED,
            autoDeleteAfterUpload = true,
        )

        every { settingsRepository.flow } returns flowOf(appSettings)
        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.getState(queueItem.id) } returns UploadItemState.PROCESSING
        coEvery { repository.markSucceeded(queueItem.id) } returns Unit
        coEvery { repository.findSourceForItem(queueItem.id) } returns sourceInfo
        coEvery { deletionQueueRepository.enqueue(any()) } returns 1
        coEvery { repository.hasQueued() } returns false
        coEvery { taskRunner.run(any()) } returns UploadTaskResult.Success(
            completionState = UploadTaskRunner.DeleteCompletionState.DELETED,
            bytesSent = 100L,
            totalBytes = 100L,
        )
        every { constraintsHelper.buildConstraints() } returns Constraints.NONE
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)

        val worker = UploadProcessorWorker(
            context,
            workerParams,
            repository,
            deletionQueueRepository,
            settingsRepository,
            workManagerProvider,
            constraintsHelper,
            taskRunner,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { repository.markSucceeded(queueItem.id) }
        coVerify { repository.findSourceForItem(queueItem.id) }
        coVerify {
            deletionQueueRepository.enqueue(
                match { requests ->
                    requests.size == 1 &&
                    requests[0].mediaId == 999L &&
                    requests[0].contentUri == sourceInfo.uri.toString() &&
                    requests[0].displayName == "photo.jpg" &&
                    requests[0].sizeBytes == 12345L &&
                    requests[0].reason == "uploaded_cleanup"
                }
            )
        }
        coVerify { deletionQueueRepository.markUploading(listOf(999L), true) }
        coVerify { deletionQueueRepository.markUploading(listOf(999L), false) }
    }

    @Test
    fun `worker does not enqueue deletion when autoDeleteAfterUpload is disabled`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val deletionQueueRepository = mockk<DeletionQueueRepository>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()
        val workManager = mockk<WorkManager>()
        val workManagerProvider = WorkManagerProvider { workManager }
        val constraintsHelper = mockk<UploadConstraintsHelper>()
        val taskRunner = mockk<UploadTaskRunner>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 5L,
            uri = Uri.parse("content://example/no_delete/111"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 54321L,
        )

        val appSettings = AppSettings(
            baseUrl = "https://example.com",
            appLogging = true,
            httpLogging = true,
            persistentQueueNotification = false,
            previewQuality = PreviewQuality.BALANCED,
            autoDeleteAfterUpload = false,
        )

        every { settingsRepository.flow } returns flowOf(appSettings)
        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.getState(queueItem.id) } returns UploadItemState.PROCESSING
        coEvery { repository.markSucceeded(queueItem.id) } returns Unit
        coEvery { repository.hasQueued() } returns false
        coEvery { taskRunner.run(any()) } returns UploadTaskResult.Success(
            completionState = UploadTaskRunner.DeleteCompletionState.DELETED,
            bytesSent = 100L,
            totalBytes = 100L,
        )
        every { constraintsHelper.buildConstraints() } returns Constraints.NONE
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)

        val worker = UploadProcessorWorker(
            context,
            workerParams,
            repository,
            deletionQueueRepository,
            settingsRepository,
            workManagerProvider,
            constraintsHelper,
            taskRunner,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { repository.markSucceeded(queueItem.id) }
        coVerify(exactly = 0) { repository.findSourceForItem(any()) }
        coVerify(exactly = 0) { deletionQueueRepository.enqueue(any()) }
        coVerify { deletionQueueRepository.markUploading(listOf(111L), true) }
        coVerify { deletionQueueRepository.markUploading(listOf(111L), false) }
    }

    @Test
    fun `worker marks uploading false even when upload fails`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val deletionQueueRepository = mockk<DeletionQueueRepository>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()
        val workManager = mockk<WorkManager>()
        val workManagerProvider = WorkManagerProvider { workManager }
        val constraintsHelper = mockk<UploadConstraintsHelper>()
        val taskRunner = mockk<UploadTaskRunner>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 6L,
            uri = Uri.parse("content://example/failed/222"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 1000L,
        )

        val appSettings = AppSettings(
            baseUrl = "https://example.com",
            appLogging = true,
            httpLogging = true,
            persistentQueueNotification = false,
            previewQuality = PreviewQuality.BALANCED,
            autoDeleteAfterUpload = true,
        )

        every { settingsRepository.flow } returns flowOf(appSettings)
        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.getState(queueItem.id) } returns UploadItemState.PROCESSING
        coEvery { repository.markFailed(any(), any(), any(), any(), any()) } returns Unit
        coEvery { repository.hasQueued() } returns false
        coEvery { taskRunner.run(any()) } returns UploadTaskResult.Failure(
            errorKind = com.kotopogoda.uploader.core.work.UploadErrorKind.NETWORK,
            httpCode = 500,
            retryable = true,
        )
        every { constraintsHelper.buildConstraints() } returns Constraints.NONE
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)

        val worker = UploadProcessorWorker(
            context,
            workerParams,
            repository,
            deletionQueueRepository,
            settingsRepository,
            workManagerProvider,
            constraintsHelper,
            taskRunner,
        )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        coVerify { repository.markFailed(queueItem.id, any(), any(), any(), any()) }
        coVerify(exactly = 0) { deletionQueueRepository.enqueue(any()) }
        coVerify { deletionQueueRepository.markUploading(listOf(222L), true) }
        coVerify { deletionQueueRepository.markUploading(listOf(222L), false) }
    }
}
