package com.kotopogoda.uploader.core.network.upload

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkContinuation
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
        val uploadRequestSlot = slot<OneTimeWorkRequest>()
        val pollRequestSlot = slot<OneTimeWorkRequest>()

        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns false
        every { constraintsProvider.buildConstraints() } returns Constraints.NONE
        every { constraintsProvider.shouldUseExpeditedWork() } returns false
        val continuation = mockk<WorkContinuation>()
        val finalContinuation = mockk<WorkContinuation>()
        every {
            workManager.beginUniqueWork(
                any(),
                any(),
                capture(uploadRequestSlot)
            )
        } returns continuation
        every { continuation.then(capture(pollRequestSlot)) } returns finalContinuation
        every { finalContinuation.enqueue() } returns mockk(relaxed = true)

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
            workManager.beginUniqueWork(
                UploadEnqueuer.uniqueNameForUri(queueItem.uri),
                ExistingWorkPolicy.KEEP,
                uploadRequestSlot.captured,
            )
        }
        verify { continuation.then(pollRequestSlot.captured) }
        verify { finalContinuation.enqueue() }

        val input = uploadRequestSlot.captured.workSpec.input
        assertEquals(queueItem.id, input.getLong(UploadEnqueuer.KEY_ITEM_ID, -1))
        assertEquals(queueItem.uri.toString(), input.getString(UploadEnqueuer.KEY_URI))
        assertEquals(queueItem.idempotencyKey, input.getString(UploadEnqueuer.KEY_IDEMPOTENCY_KEY))
        assertEquals(queueItem.displayName, input.getString(UploadEnqueuer.KEY_DISPLAY_NAME))

        val pollInput = pollRequestSlot.captured.workSpec.input
        assertEquals(queueItem.id, pollInput.getLong(UploadEnqueuer.KEY_ITEM_ID, -1))
        assertEquals(queueItem.uri.toString(), pollInput.getString(UploadEnqueuer.KEY_URI))
        assertEquals(queueItem.displayName, pollInput.getString(UploadEnqueuer.KEY_DISPLAY_NAME))

        val uploadTags = uploadRequestSlot.captured.tags
        val pollTags = pollRequestSlot.captured.tags
        assertTrue(uploadTags.contains(UploadTags.TAG_UPLOAD))
        assertTrue(uploadTags.contains(UploadTags.uniqueTag(UploadEnqueuer.uniqueNameForUri(queueItem.uri))))
        assertTrue(uploadTags.contains(UploadTags.uriTag(queueItem.uri.toString())))
        assertTrue(uploadTags.contains(UploadTags.displayNameTag(queueItem.displayName)))
        assertTrue(uploadTags.contains(UploadTags.keyTag(queueItem.idempotencyKey)))
        assertTrue(uploadTags.contains(UploadTags.kindTag(UploadWorkKind.UPLOAD)))

        assertTrue(pollTags.contains(UploadTags.TAG_POLL))
        assertTrue(pollTags.contains(UploadTags.uniqueTag(UploadEnqueuer.uniqueNameForUri(queueItem.uri))))
        assertTrue(pollTags.contains(UploadTags.uriTag(queueItem.uri.toString())))
        assertTrue(pollTags.contains(UploadTags.displayNameTag(queueItem.displayName)))
        assertTrue(pollTags.contains(UploadTags.keyTag(queueItem.idempotencyKey)))
        assertTrue(pollTags.contains(UploadTags.kindTag(UploadWorkKind.POLL)))

        assertFalse(uploadRequestSlot.captured.workSpec.expedited)
        assertFalse(pollRequestSlot.captured.workSpec.expedited)
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

        val uploadRequestSlot = slot<OneTimeWorkRequest>()
        val pollRequestSlot = slot<OneTimeWorkRequest>()
        val drainRequestSlot = slot<OneTimeWorkRequest>()
        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns true
        every { constraintsProvider.buildConstraints() } returns Constraints.NONE
        every { constraintsProvider.shouldUseExpeditedWork() } returns false
        val continuation = mockk<WorkContinuation>()
        val finalContinuation = mockk<WorkContinuation>()
        every {
            workManager.beginUniqueWork(
                any(),
                any(),
                capture(uploadRequestSlot),
            )
        } returns continuation
        every { continuation.then(capture(pollRequestSlot)) } returns finalContinuation
        every { finalContinuation.enqueue() } returns mockk(relaxed = true)
        every {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                capture(drainRequestSlot),
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
        verify {
            workManager.beginUniqueWork(
                UploadEnqueuer.uniqueNameForUri(queueItem.uri),
                ExistingWorkPolicy.KEEP,
                uploadRequestSlot.captured,
            )
        }
        verify { continuation.then(pollRequestSlot.captured) }
        verify { finalContinuation.enqueue() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                drainRequestSlot.captured,
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
        val uploadRequestSlot = slot<OneTimeWorkRequest>()
        val pollRequestSlot = slot<OneTimeWorkRequest>()
        val drainRequestSlot = slot<OneTimeWorkRequest>()
        val continuation = mockk<WorkContinuation>()
        val finalContinuation = mockk<WorkContinuation>()

        coEvery { repository.recoverStuckProcessing() } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns true
        every { constraintsProvider.buildConstraints() } returns Constraints.NONE
        every { constraintsProvider.shouldUseExpeditedWork() } returns true
        every {
            workManager.beginUniqueWork(
                any(),
                any(),
                capture(uploadRequestSlot),
            )
        } returns continuation
        every { continuation.then(capture(pollRequestSlot)) } returns finalContinuation
        every { finalContinuation.enqueue() } returns mockk(relaxed = true)
        every {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                capture(drainRequestSlot),
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
        assertTrue(uploadRequestSlot.captured.workSpec.expedited)
        assertEquals(
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            uploadRequestSlot.captured.workSpec.outOfQuotaPolicy,
        )
        assertTrue(pollRequestSlot.captured.workSpec.expedited)
        assertEquals(
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            pollRequestSlot.captured.workSpec.outOfQuotaPolicy,
        )
        assertTrue(drainRequestSlot.captured.workSpec.expedited)
        assertEquals(
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            drainRequestSlot.captured.workSpec.outOfQuotaPolicy,
        )
    }
}
