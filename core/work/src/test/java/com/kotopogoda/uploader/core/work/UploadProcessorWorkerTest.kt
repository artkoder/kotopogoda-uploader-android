package com.kotopogoda.uploader.core.work

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ListenableWorker.Result
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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

    private val context: Context = mockk(relaxed = true)

    init {
        every { context.applicationContext } returns context
    }

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
        coEvery { repository.markProcessing(queueItem.id) } returns Unit
        coEvery { repository.markSucceeded(queueItem.id) } returns Unit
        coEvery { repository.hasQueued() } returns false
        coEvery { taskRunner.run(any()) } returns UploadTaskResult.Success
        every { constraintsHelper.buildConstraints() } returns Constraints.NONE
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
        coVerify { repository.markSucceeded(queueItem.id) }
    }
}
