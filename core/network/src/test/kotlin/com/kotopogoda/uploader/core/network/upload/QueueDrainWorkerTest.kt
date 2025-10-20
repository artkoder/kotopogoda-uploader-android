package com.kotopogoda.uploader.core.network.upload

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kotopogoda.uploader.core.data.upload.UploadQueueItem
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.any
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.UUID
import javax.inject.Provider

class QueueDrainWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var logTree: RecordingTree

    @Before
    fun setUp() {
        QueueDrainWorker.resetEnqueuePolicy()
        Timber.uprootAll()
        logTree = RecordingTree()
        Timber.plant(logTree)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `worker enqueues upload work with expected metadata`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val constraintsState = MutableStateFlow<Constraints?>(Constraints.NONE)
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 1L,
            uri = Uri.parse("content://example/test"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )
        val requestSlot = slot<OneTimeWorkRequest>()

        val thresholdSlot = slot<Long>()
        coEvery { repository.recoverStuckProcessing(capture(thresholdSlot)) } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns false
        every { constraintsProvider.constraintsState } returns constraintsState
        coEvery { constraintsProvider.awaitConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { constraintsProvider.shouldUseExpeditedWork() } returns true
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
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { repository.recoverStuckProcessing(any()) }
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

        assertTrue(requestSlot.captured.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, requestSlot.captured.workSpec.outOfQuotaPolicy)

        val capturedThreshold = thresholdSlot.captured
        val now = System.currentTimeMillis()
        val toleranceMs = 5_000L
        assertTrue(
            abs((capturedThreshold + UploadQueueRepository.STUCK_TIMEOUT_MS) - now) <= toleranceMs,
            "recoverStuckProcessing threshold should correspond to current time minus timeout",
        )

        logTree.assertActionLogged("drain_worker_start")
        logTree.assertActionLogged("drain_worker_batch")
        logTree.assertActionLogged(
            action = "drain_worker_processing_success",
            predicate = { it.contains("itemId=${queueItem.id}") },
        )
        logTree.assertActionLogged(
            action = "drain_worker_enqueue_upload",
            predicate = { it.contains("uniqueName=${UploadEnqueuer.uniqueNameForUri(queueItem.uri)}") },
        )
        logTree.assertActionLogged(
            action = "drain_worker_complete",
            predicate = { it.contains("result=success") },
        )
    }

    @Test
    fun `worker logs skip when item cannot be marked processing`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val constraintsState = MutableStateFlow<Constraints?>(Constraints.NONE)
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 7L,
            uri = Uri.parse("content://example/skip"),
            idempotencyKey = "skip",
            displayName = "photo.jpg",
            size = 10L,
        )

        coEvery { repository.recoverStuckProcessing(any()) } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns false
        coEvery { repository.hasQueued() } returns false
        every { constraintsProvider.constraintsState } returns constraintsState
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { constraintsProvider.shouldUseExpeditedWork() } returns true

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(
                UploadEnqueuer.uniqueNameForUri(queueItem.uri),
                any(),
                any(),
            )
        }

        logTree.assertActionLogged(
            action = "drain_worker_processing_skip",
            predicate = { it.contains("itemId=${queueItem.id}") },
        )
    }

    @Test
    fun `worker retries and logs error when exception occurs on first attempt`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)

        every { workerParams.runAttemptCount } returns 0
        every { constraintsProvider.constraintsState } returns MutableStateFlow(null)
        coEvery { repository.recoverStuckProcessing(any()) } throws IllegalStateException("boom")

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        logTree.assertErrorLogged(
            action = "drain_worker_error",
            predicate = {
                it.message?.contains("willRetry=true") == true &&
                    it.throwable is IllegalStateException
            },
        )
    }

    @Test
    fun `worker fails with message when exception persists after retries`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)

        every { workerParams.runAttemptCount } returns QueueDrainWorker.MAX_ATTEMPTS_BEFORE_FAILURE
        every { constraintsProvider.constraintsState } returns MutableStateFlow(null)
        coEvery { repository.recoverStuckProcessing(any()) } throws RuntimeException("still broken")

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertTrue(result is Result.Failure)
        assertEquals(
            "still broken",
            result.outputData.getString(QueueDrainWorker.FAILURE_MESSAGE_KEY),
        )
        logTree.assertErrorLogged(
            action = "drain_worker_error",
            predicate = {
                it.message?.contains("willRetry=false") == true &&
                    it.throwable is RuntimeException
            },
        )
    }

    @Test
    fun `worker triggers stuck processing recovery before draining queue`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)

        coEvery { repository.recoverStuckProcessing(any()) } returns 2
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns emptyList()
        coEvery { repository.hasQueued() } returns false

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 1) { repository.recoverStuckProcessing(any()) }
        coVerify(exactly = 1) { repository.fetchQueued(5, recoverStuck = false) }

        logTree.assertActionLogged("drain_worker_start")
        logTree.assertActionLogged(
            action = "drain_worker_batch",
            predicate = { it.contains("fetched=0") },
        )
        logTree.assertActionLogged(
            action = "drain_worker_complete",
            predicate = { it.contains("result=no_items") },
        )
    }

    @Test
    fun `worker chain reset requeues only stale processing items`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val constraintsState = MutableStateFlow<Constraints?>(Constraints.NONE)
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val stuckStartedAt = System.currentTimeMillis() - UploadQueueRepository.STUCK_TIMEOUT_MS - 10_000L

        val head = mockk<WorkInfo>()
        val headId = UUID.randomUUID()
        every { head.state } returns WorkInfo.State.RUNNING
        every { head.progress } returns workDataOf(
            QueueDrainWorker.PROGRESS_KEY_STARTED_AT to stuckStartedAt,
        )
        every { head.nextScheduleTimeMillis } returns stuckStartedAt
        every { head.id } returns headId
        val future = mockk<ListenableFuture<List<WorkInfo>>>()
        every { future.get() } returns listOf(head)

        val states = mutableMapOf(
            11L to UploadItemState.PROCESSING,
            12L to UploadItemState.PROCESSING,
        )
        val updatedAt = mutableMapOf(
            11L to stuckStartedAt,
            12L to System.currentTimeMillis(),
        )

        coEvery { repository.recoverStuckProcessing(any()) } answers {
            val threshold = firstArg<Long>()
            var requeued = 0
            states.forEach { (id, state) ->
                val lastUpdated = updatedAt.getValue(id)
                if (state == UploadItemState.PROCESSING && lastUpdated <= threshold) {
                    states[id] = UploadItemState.QUEUED
                    requeued += 1
                }
            }
            requeued
        }
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns emptyList()
        coEvery { repository.hasQueued() } returns true

        every { constraintsProvider.constraintsState } returns constraintsState
        every { constraintsProvider.shouldUseExpeditedWork() } returns false
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { workManager.getWorkInfosForUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns future
        every { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns mockk(relaxed = true)
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                any<OneTimeWorkRequest>(),
            )
        } returns mockk(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(UploadItemState.QUEUED, states[11L])
        assertEquals(UploadItemState.PROCESSING, states[12L])
        coVerify { repository.recoverStuckProcessing(any()) }
        verify { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
        logTree.assertActionLogged(
            action = "drain_worker_chain_snapshot",
            predicate = {
                it.contains("source=worker") &&
                    it.contains("count=1") &&
                    it.contains("states=$headId:RUNNING")
            },
        )
    }

    @Test
    fun `worker chain reset cancels failed work immediately`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val constraintsState = MutableStateFlow<Constraints?>(Constraints.NONE)
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val failureAt = System.currentTimeMillis()
        val stuckStartedAt = System.currentTimeMillis() - UploadQueueRepository.STUCK_TIMEOUT_MS - 10_000L

        val failed = mockk<WorkInfo>()
        val failedId = UUID.randomUUID()
        every { failed.state } returns WorkInfo.State.FAILED
        every { failed.progress } returns workDataOf(
            QueueDrainWorker.PROGRESS_KEY_STARTED_AT to stuckStartedAt,
        )
        every { failed.outputData } returns workDataOf(
            QueueDrainWorker.FAILURE_MESSAGE_KEY to "boom",
            QueueDrainWorker.FAILURE_AT_KEY to failureAt,
        )
        every { failed.nextScheduleTimeMillis } returns 0L
        every { failed.id } returns failedId

        val future = mockk<ListenableFuture<List<WorkInfo>>>()
        every { future.get() } returns listOf(failed)

        val states = mutableMapOf(
            21L to UploadItemState.PROCESSING,
            22L to UploadItemState.PROCESSING,
        )
        val updatedAt = mutableMapOf(
            21L to stuckStartedAt,
            22L to System.currentTimeMillis(),
        )

        coEvery { repository.recoverStuckProcessing(any()) } answers {
            val threshold = firstArg<Long>()
            var requeued = 0
            states.forEach { (id, state) ->
                val lastUpdated = updatedAt.getValue(id)
                if (state == UploadItemState.PROCESSING && lastUpdated <= threshold) {
                    states[id] = UploadItemState.QUEUED
                    requeued += 1
                }
            }
            requeued
        }
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns emptyList()
        coEvery { repository.hasQueued() } returns true

        every { constraintsProvider.constraintsState } returns constraintsState
        every { constraintsProvider.shouldUseExpeditedWork() } returns false
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { workManager.getWorkInfosForUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns future
        every { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns mockk(relaxed = true)
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                any<OneTimeWorkRequest>(),
            )
        } returns mockk(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(UploadItemState.QUEUED, states[21L])
        assertEquals(UploadItemState.PROCESSING, states[22L])
        coVerify { repository.recoverStuckProcessing(any()) }
        verify { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
        logTree.assertActionLogged(
            action = "drain_worker_chain_snapshot",
            predicate = {
                it.contains("source=worker") &&
                    it.contains("count=1") &&
                    it.contains("states=$failedId:FAILED")
            },
        )
        logTree.assertActionLogged(
            action = "drain_worker_chain_failed",
            predicate = {
                it.contains("workId=$failedId") &&
                    it.contains("failureMessage=boom") &&
                    it.contains("checked=1")
            },
        )
    }

    @Test
    fun `worker chain reset inspects all enqueued work infos`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val constraintsState = MutableStateFlow<Constraints?>(Constraints.NONE)
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val now = System.currentTimeMillis()
        val freshStartedAt = now - UploadQueueRepository.STUCK_TIMEOUT_MS / 2
        val staleStartedAt = now - UploadQueueRepository.STUCK_TIMEOUT_MS - 10_000L

        val fresh = mockk<WorkInfo>()
        val freshId = UUID.randomUUID()
        every { fresh.state } returns WorkInfo.State.ENQUEUED
        every { fresh.progress } returns workDataOf(
            QueueDrainWorker.PROGRESS_KEY_STARTED_AT to freshStartedAt,
        )
        every { fresh.nextScheduleTimeMillis } returns freshStartedAt
        every { fresh.id } returns freshId

        val staleId = UUID.randomUUID()
        val stale = mockk<WorkInfo>()
        every { stale.state } returns WorkInfo.State.RUNNING
        every { stale.progress } returns workDataOf(
            QueueDrainWorker.PROGRESS_KEY_STARTED_AT to staleStartedAt,
        )
        every { stale.nextScheduleTimeMillis } returns staleStartedAt
        every { stale.id } returns staleId

        val future = mockk<ListenableFuture<List<WorkInfo>>>()
        every { future.get() } returns listOf(fresh, stale)

        coEvery { repository.recoverStuckProcessing(any()) } returns 1
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns emptyList()
        coEvery { repository.hasQueued() } returns false

        every { constraintsProvider.constraintsState } returns constraintsState
        every { constraintsProvider.shouldUseExpeditedWork() } returns false
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { workManager.getWorkInfosForUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns future
        every { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns mockk(relaxed = true)
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                any<OneTimeWorkRequest>(),
            )
        } returns mockk(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { repository.recoverStuckProcessing(any()) }
        verify { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
        logTree.assertActionLogged(
            action = "drain_worker_chain_snapshot",
            predicate = {
                it.contains("source=worker") &&
                    it.contains("count=2") &&
                    it.contains("states=$freshId:ENQUEUED;$staleId:RUNNING")
            },
        )
        logTree.assertActionLogged(
            action = "drain_worker_chain_stuck",
            predicate = {
                it.contains("workId=$staleId") &&
                    it.contains("checked=2")
            },
        )
    }

    @Test
    fun `worker proceeds when constraints available`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 4L,
            uri = Uri.parse("content://example/safe-default"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )

        coEvery { repository.recoverStuckProcessing(any()) } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns false
        coEvery { constraintsProvider.awaitConstraints() } returns Constraints.NONE
        every { constraintsProvider.shouldUseExpeditedWork() } returns false
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                any<OneTimeWorkRequest>()
            )
        } returns mockk(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify { repository.markProcessing(queueItem.id) }
        verify {
            workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `worker enqueues upload work with connected constraints`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = UploadConstraintsHelper()
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 5L,
            uri = Uri.parse("content://example/after-preference"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )
        val names = mutableListOf<String>()
        val policies = mutableListOf<ExistingWorkPolicy>()
        val requests = mutableListOf<OneTimeWorkRequest>()

        coEvery { repository.recoverStuckProcessing(any()) } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns false
        every {
            workManager.enqueueUniqueWork(
                capture(names),
                capture(policies),
                capture(requests),
            )
        } returns mockk(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(listOf(UploadEnqueuer.uniqueNameForUri(queueItem.uri)), names)
        assertEquals(listOf(ExistingWorkPolicy.KEEP), policies)
        val uploadRequest = requests.single()
        assertEquals(NetworkType.CONNECTED, uploadRequest.workSpec.constraints.requiredNetworkType)
        assertTrue(uploadRequest.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, uploadRequest.workSpec.outOfQuotaPolicy)

        logTree.assertActionLogged("drain_worker_enqueue_upload")
    }

    @Test
    fun `worker reschedules itself when queue not empty`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val constraintsState = MutableStateFlow<Constraints?>(Constraints.NONE)
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 2L,
            uri = Uri.parse("content://example/next"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )

        coEvery { repository.recoverStuckProcessing(any()) } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns true
        every { constraintsProvider.constraintsState } returns constraintsState
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { constraintsProvider.shouldUseExpeditedWork() } returns true
        every { workManager.enqueueUniqueWork(any(), any(), any()) } returns mockk(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
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
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any(),
            )
        }

        logTree.assertActionLogged("drain_worker_reschedule")
    }

    @Test
    fun `worker reschedules without network constraints when state empty`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val constraintsState = MutableStateFlow<Constraints?>(null)
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 9L,
            uri = Uri.parse("content://example/build"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )

        coEvery { repository.recoverStuckProcessing(any()) } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns true
        coEvery { constraintsProvider.awaitConstraints() } returns Constraints.NONE
        every { constraintsProvider.constraintsState } returns constraintsState
        every { constraintsProvider.shouldUseExpeditedWork() } returns true
        val uploadRequestSlot = slot<OneTimeWorkRequest>()
        val drainRequestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                UploadEnqueuer.uniqueNameForUri(queueItem.uri),
                ExistingWorkPolicy.KEEP,
                capture(uploadRequestSlot),
            )
        } returns mockk(relaxed = true)
        every {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                capture(drainRequestSlot),
            )
        } returns mockk(relaxed = true)
        val future = mockk<ListenableFuture<List<WorkInfo>>>()
        every { future.get() } returns emptyList()
        every { workManager.getWorkInfosForUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns future

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        verify(exactly = 0) { constraintsProvider.buildConstraints() }
        assertEquals(Constraints.NONE, uploadRequestSlot.captured.workSpec.constraints)
        assertEquals(Constraints.NONE, drainRequestSlot.captured.workSpec.constraints)

        logTree.assertActionLogged(
            action = "drain_worker_constraints_missing",
            predicate = { it.contains("source=worker") },
        )
        logTree.assertActionLogged(
            action = "drain_worker_constraints_built",
            predicate = { it.contains("source=worker") },
        )
    }

    @Test
    fun `worker queues sequential drain when queue exceeds batch size`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val constraintsState = MutableStateFlow<Constraints?>(Constraints.NONE)
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItems = (0 until 5).map { index ->
            UploadQueueItem(
                id = index.toLong(),
                uri = Uri.parse("content://example/overflow/$index"),
                idempotencyKey = "key-$index",
                displayName = "photo-$index.jpg",
                size = 10L,
            )
        }
        val names = mutableListOf<String>()
        val policies = mutableListOf<ExistingWorkPolicy>()

        coEvery { repository.recoverStuckProcessing(any()) } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns queueItems
        coEvery { repository.markProcessing(any()) } returns true
        coEvery { repository.hasQueued() } returns true
        every { constraintsProvider.constraintsState } returns constraintsState
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { constraintsProvider.shouldUseExpeditedWork() } returns true
        every {
            workManager.enqueueUniqueWork(
                capture(names),
                capture(policies),
                any(),
            )
        } returns mockk(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(6, names.size)
        val drainIndex = names.indexOf(QUEUE_DRAIN_WORK_NAME)
        assertTrue(drainIndex >= 0)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, policies[drainIndex])

        logTree.assertActionLogged("drain_worker_reschedule")
    }

    @Test
    fun `worker marks requests as expedited when configured`() = runTest {
        val repository = mockk<UploadQueueRepository>()
        val workManager = mockk<WorkManager>()
        val constraintsProvider = mockk<UploadConstraintsProvider>()
        val constraintsState = MutableStateFlow<Constraints?>(Constraints.NONE)
        val workerParams = mockk<WorkerParameters>(relaxed = true)
        val queueItem = UploadQueueItem(
            id = 3L,
            uri = Uri.parse("content://example/expedited"),
            idempotencyKey = "idempotency",
            displayName = "photo.jpg",
            size = 10L,
        )
        val names = mutableListOf<String>()
        val policies = mutableListOf<ExistingWorkPolicy>()
        val requests = mutableListOf<OneTimeWorkRequest>()

        coEvery { repository.recoverStuckProcessing(any()) } returns 0
        coEvery { repository.fetchQueued(any(), recoverStuck = false) } returns listOf(queueItem)
        coEvery { repository.markProcessing(queueItem.id) } returns true
        coEvery { repository.hasQueued() } returns true
        every { constraintsProvider.constraintsState } returns constraintsState
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { constraintsProvider.shouldUseExpeditedWork() } returns true
        every {
            workManager.enqueueUniqueWork(
                capture(names),
                capture(policies),
                capture(requests),
            )
        } returns mockk(relaxed = true)

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            providerOf(workManager),
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(2, requests.size)
        assertTrue(names.contains(UploadEnqueuer.uniqueNameForUri(queueItem.uri)))
        assertTrue(names.contains(QUEUE_DRAIN_WORK_NAME))
        val uploadRequest = requests[names.indexOf(UploadEnqueuer.uniqueNameForUri(queueItem.uri))]
        val drainRequest = requests[names.indexOf(QUEUE_DRAIN_WORK_NAME)]
        val drainPolicy = policies[names.indexOf(QUEUE_DRAIN_WORK_NAME)]

        assertTrue(uploadRequest.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, uploadRequest.workSpec.outOfQuotaPolicy)
        assertTrue(!drainRequest.workSpec.expedited)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, drainPolicy)

        logTree.assertActionLogged(
            action = "drain_worker_reschedule",
            predicate = { !it.contains("expedited=") },
        )
    }

    private fun RecordingTree.assertActionLogged(action: String, predicate: (String) -> Boolean = { true }) {
        val messages = logs.filter { entry ->
            entry.tag == "WorkManager" &&
                entry.message?.contains("action=$action") == true &&
                predicate(entry.message!!)
        }
        assertTrue(messages.isNotEmpty(), "Ожидалось наличие лога с action=$action")
    }

    private fun RecordingTree.assertErrorLogged(
        action: String,
        predicate: (LogEntry) -> Boolean = { true },
    ) {
        val messages = logs.filter { entry ->
            entry.tag == "WorkManager" &&
                entry.priority == Log.ERROR &&
                entry.message?.contains("action=$action") == true &&
                predicate(entry)
        }
        assertTrue(messages.isNotEmpty(), "Ожидалось наличие error-лога с action=$action")
    }

    private class RecordingTree : Timber.DebugTree() {
        val logs = mutableListOf<LogEntry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += LogEntry(priority, tag, message, t)
        }

        fun clear() {
            logs.clear()
        }
    }

    private data class LogEntry(
        val priority: Int,
        val tag: String?,
        val message: String?,
        val throwable: Throwable?,
    )
}

private fun providerOf(workManager: WorkManager): Provider<WorkManager> = Provider { workManager }
