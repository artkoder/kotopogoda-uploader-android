package com.kotopogoda.uploader.core.network.upload

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.ListenableWorker.Result
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueItem
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner
import com.kotopogoda.uploader.core.network.upload.UploadTaskRunner.UploadTaskResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UploadProcessorWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `worker recovers stuck processing before fetching batch`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsHelper = mockk<UploadConstraintsHelper>()
        val taskRunner = mockk<UploadTaskRunner>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 1L,
            uri = Uri.parse("content://example/test"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )

        coEvery { repository.recoverStuckProcessing() } returns 1
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.getState(queueItem.id) } returns UploadItemState.PROCESSING
        coEvery { repository.markSucceeded(queueItem.id) } returns Unit
        coEvery { repository.hasQueued() } returns false
        coEvery { taskRunner.run(any()) } returns UploadTaskResult.Success
        coEvery { constraintsHelper.awaitConstraints() } returns Constraints.NONE
        every { workManager.enqueueUniqueWork(any(), any(), any()) } returns mockk<Operation>(relaxed = true)

        val worker = UploadProcessorWorker(
            context,
            workerParams,
            repository,
            workManager,
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
    }

    @Test
    fun `worker skips state updates when item no longer processing`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsHelper = mockk<UploadConstraintsHelper>()
        val taskRunner = mockk<UploadTaskRunner>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 2L,
            uri = Uri.parse("content://example/skip"),
            idempotencyKey = "idempotency", 
            displayName = "photo.jpg",
            size = 10L,
        )

        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.getState(queueItem.id) } returns UploadItemState.FAILED
        coEvery { repository.hasQueued() } returns false
        coEvery { taskRunner.run(any()) } returns UploadTaskResult.Success
        coEvery { constraintsHelper.awaitConstraints() } returns Constraints.NONE
        every { workManager.enqueueUniqueWork(any(), any(), any()) } returns mockk(relaxed = true)

        val worker = UploadProcessorWorker(
            context,
            workerParams,
            repository,
            workManager,
            constraintsHelper,
            taskRunner,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { repository.getState(queueItem.id) }
        coVerify(exactly = 0) { repository.markSucceeded(queueItem.id) }
        coVerify(exactly = 0) { repository.markFailed(any(), any(), any(), any()) }
    }

    @Test
    fun `worker skips upload when item cannot transition to processing`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsHelper = mockk<UploadConstraintsHelper>()
        val taskRunner = mockk<UploadTaskRunner>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 3L,
            uri = Uri.parse("content://example/cancelled"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )

        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns false
        coEvery { repository.hasQueued() } returns false
        coEvery { constraintsHelper.awaitConstraints() } returns Constraints.NONE
        every { workManager.enqueueUniqueWork(any(), any(), any()) } returns mockk(relaxed = true)

        val worker = UploadProcessorWorker(
            context,
            workerParams,
            repository,
            workManager,
            constraintsHelper,
            taskRunner,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { repository.markProcessing(queueItem.id) }
        coVerify(exactly = 0) { taskRunner.run(any()) }
    }

    @Test
    fun `worker waits for constraints before rescheduling when queue empty`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsHelper = mockk<UploadConstraintsHelper>()
        val taskRunner = mockk<UploadTaskRunner>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)

        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns emptyList()
        coEvery { repository.hasQueued() } returns true
        coEvery { constraintsHelper.awaitConstraints() } returns Constraints.NONE
        every { workManager.enqueueUniqueWork(any(), any(), any()) } returns mockk(relaxed = true)

        val worker = UploadProcessorWorker(
            context,
            workerParams,
            repository,
            workManager,
            constraintsHelper,
            taskRunner,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { constraintsHelper.awaitConstraints() }
    }
}
