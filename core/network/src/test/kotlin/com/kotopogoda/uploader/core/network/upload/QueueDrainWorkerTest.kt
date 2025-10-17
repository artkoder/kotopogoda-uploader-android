package com.kotopogoda.uploader.core.network.upload

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.data.upload.UploadQueueItem
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class QueueDrainWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `worker enqueues upload work with expected metadata`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 1L,
            uri = Uri.parse("content://example/test"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )
        val requestSlot = slot<OneTimeWorkRequest>()

        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns false
        every { constraintsProvider.buildConstraints() } returns Constraints.NONE
        every { constraintsProvider.shouldUseExpeditedWork() } returns false
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                capture(requestSlot)
            )
        } returns mockk<Operation>(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            workManager,
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { repository.recoverStuckProcessing() }
        coVerify { repository.fetchQueued(match { it == 5 }, recoverStuck = false) }
        coVerify { repository.markProcessing(queueItem.id) }
        verify {
            workManager.enqueueUniqueWork(
                UploadEnqueuer.uniqueNameForUri(queueItem.uri),
                ExistingWorkPolicy.KEEP,
                requestSlot.captured,
            )
        }

        val input = requestSlot.captured.workSpec.input
        assertEquals(queueItem.id, input.getLong(UploadEnqueuer.KEY_ITEM_ID, -1))
        assertEquals(queueItem.uri.toString(), input.getString(UploadEnqueuer.KEY_URI))
        assertEquals(queueItem.idempotencyKey, input.getString(UploadEnqueuer.KEY_IDEMPOTENCY_KEY))
        assertEquals(queueItem.displayName, input.getString(UploadEnqueuer.KEY_DISPLAY_NAME))

        val tags = requestSlot.captured.tags
        assertTrue(tags.contains(UploadTags.TAG_UPLOAD))
        assertTrue(tags.contains(UploadTags.uniqueTag(UploadEnqueuer.uniqueNameForUri(queueItem.uri))))
        assertTrue(tags.contains(UploadTags.uriTag(queueItem.uri.toString())))
        assertTrue(tags.contains(UploadTags.displayNameTag(queueItem.displayName)))
        assertTrue(tags.contains(UploadTags.keyTag(queueItem.idempotencyKey)))
        assertTrue(tags.contains(UploadTags.kindTag(UploadWorkKind.UPLOAD)))

        assertFalse(requestSlot.captured.workSpec.expedited)
    }

    @Test
    fun `worker reschedules itself when queue not empty`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 2L,
            uri = Uri.parse("content://example/next"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )

        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns true
        every { constraintsProvider.buildConstraints() } returns Constraints.NONE
        every { constraintsProvider.shouldUseExpeditedWork() } returns false
        every { workManager.enqueueUniqueWork(any(), any(), any()) } returns mockk(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            workManager,
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        verify {
            workManager.enqueueUniqueWork(
                UploadEnqueuer.uniqueNameForUri(queueItem.uri),
                ExistingWorkPolicy.KEEP,
                any(),
            )
        }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                any(),
            )
        }
    }

    @Test
    fun `worker marks requests as expedited when configured`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 3L,
            uri = Uri.parse("content://example/expedited"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )
        val names = mutableListOf<String>()
        val requests = mutableListOf<OneTimeWorkRequest>()

        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns true
        every { constraintsProvider.buildConstraints() } returns Constraints.NONE
        every { constraintsProvider.shouldUseExpeditedWork() } returns true
        every {
            workManager.enqueueUniqueWork(
                capture(names),
                any(),
                capture(requests),
            )
        } returns mockk(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            workManager,
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(2, requests.size)
        assertTrue(names.contains(UploadEnqueuer.uniqueNameForUri(queueItem.uri)))
        assertTrue(names.contains(QUEUE_DRAIN_WORK_NAME))
        val uploadRequest = requests[names.indexOf(UploadEnqueuer.uniqueNameForUri(queueItem.uri))]
        val drainRequest = requests[names.indexOf(QUEUE_DRAIN_WORK_NAME)]

        assertTrue(uploadRequest.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, uploadRequest.workSpec.outOfQuotaPolicy)
        assertTrue(drainRequest.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, drainRequest.workSpec.outOfQuotaPolicy)
    }
}
