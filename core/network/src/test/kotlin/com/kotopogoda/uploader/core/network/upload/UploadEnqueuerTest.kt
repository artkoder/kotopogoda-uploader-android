package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkMetadata
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.match
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.UUID
import timber.log.Timber
import javax.inject.Provider

class UploadEnqueuerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val summaryStarter = mockk<UploadSummaryStarter>(relaxed = true)
    private val uploadItemsRepository = mockk<UploadQueueRepository>(relaxed = true)
    private val constraintsProvider = mockk<UploadConstraintsProvider>()
    private val constraintsState = MutableStateFlow<Constraints?>(Constraints.NONE)
    private lateinit var logTree: RecordingTree
    private val workManagerProvider = Provider { workManager }

    init {
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)
        every { workManager.cancelUniqueWork(any()) } returns mockk(relaxed = true)
        resetConstraintMocks()
    }

    @Before
    fun setUp() {
        Timber.uprootAll()
        logTree = RecordingTree()
        Timber.plant(logTree)
        constraintsState.value = Constraints.NONE
        resetConstraintMocks()
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    private fun resetConstraintMocks() {
        every { constraintsProvider.constraintsState } returns constraintsState
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { constraintsProvider.shouldUseExpeditedWork() } returns true
    }

    private fun createEnqueuer(): UploadEnqueuer = UploadEnqueuer(
        workManagerProvider = workManagerProvider,
        summaryStarter = summaryStarter,
        uploadItemsRepository = uploadItemsRepository,
        constraintsProvider = constraintsProvider,
    )

    @Test
    fun scheduleDrain_enqueuesDrainWithoutNetworkConstraints() = runBlocking {
        val constraintsHelper = UploadConstraintsHelper()
        val enqueuer = UploadEnqueuer(
            workManagerProvider = workManagerProvider,
            summaryStarter = summaryStarter,
            uploadItemsRepository = uploadItemsRepository,
            constraintsProvider = constraintsHelper,
        )
        val policies = mutableListOf<ExistingWorkPolicy>()
        val requests = mutableListOf<OneTimeWorkRequest>()
        clearMocks(workManager, answers = false)
        every {
            workManager.enqueueUniqueWork(
                any(),
                capture(policies),
                capture(requests),
            )
        } returns mockk(relaxed = true)

        enqueuer.scheduleDrain()

        verify(timeout = 1_000L) {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }

        val request = requests.single()
        assertEquals(Constraints.NONE, request.workSpec.constraints)
        assertTrue(!request.workSpec.expedited)
        assertTrue(request.tags.contains(UploadTags.TAG_DRAIN))
        assertEquals(listOf(ExistingWorkPolicy.APPEND_OR_REPLACE), policies)
    }

    @Test
    fun scheduleDrain_doesNotBuildConstraintsWhenStateEmpty() {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        constraintsState.value = null
        resetConstraintMocks()
        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                capture(requestSlot),
            )
        } returns mockk(relaxed = true)

        enqueuer.scheduleDrain()

        verify(exactly = 0) { constraintsProvider.buildConstraints() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                requestSlot.captured,
            )
        }
        assertTrue(requestSlot.captured.tags.contains(UploadTags.TAG_DRAIN))
    }

    @Test
    fun enqueue_persistsItemAndStartsWorker() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        constraintsState.value = Constraints.NONE
        resetConstraintMocks()
        val uri = Uri.parse("content://example/1")

        enqueuer.enqueue(uri, "key-1", "file-1")

        coVerify { uploadItemsRepository.enqueue(uri, "key-1") }
        verify { summaryStarter.ensureRunning() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                match {
                    it.workSpec.constraints == Constraints.NONE &&
                        it.tags.contains(UploadTags.TAG_DRAIN)
                }
            )
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
    }

    @Test
    fun cancel_cancelsProcessorWhenItemProcessing() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        constraintsState.value = Constraints.NONE
        resetConstraintMocks()
        val uri = Uri.parse("content://example/2")
        coEvery { uploadItemsRepository.markCancelled(uri) } returns true

        enqueuer.cancel(uri)

        val uniqueTag = UploadTags.uniqueTag(enqueuer.uniqueName(uri))
        verify { workManager.cancelAllWorkByTag(uniqueTag) }
        verify { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
        coVerify { uploadItemsRepository.markCancelled(uri) }
        verify(exactly = 0) { constraintsProvider.shouldUseExpeditedWork() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                match {
                    it.workSpec.constraints == Constraints.NONE &&
                        it.tags.contains(UploadTags.TAG_DRAIN)
                }
            )
        }
    }

    @Test
    fun cancel_skipsProcessorCancellationWhenItemNotProcessing() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        constraintsState.value = Constraints.NONE
        resetConstraintMocks()
        val uri = Uri.parse("content://example/20")
        coEvery { uploadItemsRepository.markCancelled(uri) } returns false

        enqueuer.cancel(uri)

        val uniqueTag = UploadTags.uniqueTag(enqueuer.uniqueName(uri))
        verify { workManager.cancelAllWorkByTag(uniqueTag) }
        verify(exactly = 0) { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
        coVerify { uploadItemsRepository.markCancelled(uri) }
        verify(exactly = 0) { constraintsProvider.shouldUseExpeditedWork() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                match {
                    it.workSpec.constraints == Constraints.NONE &&
                        it.tags.contains(UploadTags.TAG_DRAIN)
                }
            )
        }
    }

    @Test
    fun retry_requeuesItemAndStartsWorker() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        constraintsState.value = Constraints.NONE
        resetConstraintMocks()
        val uri = Uri.parse("content://example/3")
        val metadata = UploadWorkMetadata(
            uniqueName = enqueuer.uniqueName(uri),
            uri = uri,
            displayName = "file-3",
            idempotencyKey = "key-3",
            kind = UploadWorkKind.UPLOAD,
        )

        enqueuer.retry(metadata)

        val uniqueTag = UploadTags.uniqueTag(enqueuer.uniqueName(uri))
        verify { workManager.cancelAllWorkByTag(uniqueTag) }
        coVerify { uploadItemsRepository.enqueue(uri, "key-3") }
        verify { summaryStarter.ensureRunning() }
        verify(exactly = 0) { constraintsProvider.shouldUseExpeditedWork() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                match {
                    it.workSpec.constraints == Constraints.NONE &&
                        it.tags.contains(UploadTags.TAG_DRAIN)
                }
            )
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
    }

    @Test
    fun `scheduleDrain requeues only stale processing items when resetting stuck chain`() = runBlocking {
        val enqueuer = createEnqueuer()
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
            1L to UploadItemState.PROCESSING,
            2L to UploadItemState.PROCESSING,
        )
        val updatedAt = mutableMapOf(
            1L to stuckStartedAt,
            2L to System.currentTimeMillis(),
        )

        coEvery { uploadItemsRepository.recoverStuckProcessing(any()) } answers {
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

        clearMocks(workManager, answers = false)
        every { workManager.getWorkInfosForUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns future
        every { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns mockk(relaxed = true)
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                any<OneTimeWorkRequest>(),
            )
        } returns mockk(relaxed = true)

        enqueuer.scheduleDrain()

        assertEquals(UploadItemState.QUEUED, states[1L])
        assertEquals(UploadItemState.PROCESSING, states[2L])
        coVerify { uploadItemsRepository.recoverStuckProcessing(any()) }
        verify { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
        logTree.assertActionLogged(
            action = "drain_worker_chain_snapshot",
            predicate = {
                it.contains("source=enqueuer") &&
                    it.contains("count=1") &&
                    it.contains("states=$headId:RUNNING")
            },
        )
    }

    @Test
    fun `scheduleDrain cancels failed work immediately`() = runBlocking {
        val enqueuer = createEnqueuer()
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
            31L to UploadItemState.PROCESSING,
            32L to UploadItemState.PROCESSING,
        )
        val updatedAt = mutableMapOf(
            31L to stuckStartedAt,
            32L to System.currentTimeMillis(),
        )

        coEvery { uploadItemsRepository.recoverStuckProcessing(any()) } answers {
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

        clearMocks(workManager, answers = false)
        every { workManager.getWorkInfosForUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns future
        every { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns mockk(relaxed = true)
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                any<OneTimeWorkRequest>(),
            )
        } returns mockk(relaxed = true)

        enqueuer.scheduleDrain()

        assertEquals(UploadItemState.QUEUED, states[31L])
        assertEquals(UploadItemState.PROCESSING, states[32L])
        coVerify { uploadItemsRepository.recoverStuckProcessing(any()) }
        verify { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
        logTree.assertActionLogged(
            action = "drain_worker_chain_snapshot",
            predicate = {
                it.contains("source=enqueuer") &&
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
    fun `scheduleDrain resets stale entry even when it is not the first in chain`() = runBlocking {
        val enqueuer = createEnqueuer()
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

        coEvery { uploadItemsRepository.recoverStuckProcessing(any()) } returns 1

        clearMocks(workManager, answers = false)
        every { workManager.getWorkInfosForUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns future
        every { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) } returns mockk(relaxed = true)
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                any<OneTimeWorkRequest>(),
            )
        } returns mockk(relaxed = true)

        enqueuer.scheduleDrain()

        verify { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
        coVerify { uploadItemsRepository.recoverStuckProcessing(any()) }
        logTree.assertActionLogged(
            action = "drain_worker_chain_snapshot",
            predicate = {
                it.contains("source=enqueuer") &&
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
    fun cancelAllUploads_cancelsTagsAndUpdatesRepository() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        constraintsState.value = Constraints.NONE
        resetConstraintMocks()

        enqueuer.cancelAllUploads()

        verify { workManager.cancelAllWorkByTag(UploadTags.TAG_UPLOAD) }
        verify { workManager.cancelAllWorkByTag(UploadTags.TAG_POLL) }
        verify { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
        coVerify { uploadItemsRepository.cancelAll() }
        verify(exactly = 0) { constraintsProvider.shouldUseExpeditedWork() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                match {
                    it.workSpec.constraints == Constraints.NONE &&
                        it.tags.contains(UploadTags.TAG_DRAIN)
                }
            )
        }
    }

    @Test
    fun scheduleDrain_doesNotSetExpeditedEvenWhenEnabled() {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        constraintsState.value = Constraints.NONE
        resetConstraintMocks()
        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                any(),
                any(),
                capture(requestSlot),
            )
        } returns mockk(relaxed = true)

        enqueuer.scheduleDrain()

        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                requestSlot.captured,
            )
        }
        assertTrue(!requestSlot.captured.workSpec.expedited)
        assertTrue(requestSlot.captured.tags.contains(UploadTags.TAG_DRAIN))
        verify(exactly = 0) { constraintsProvider.shouldUseExpeditedWork() }
    }

    @Test
    fun enqueue_moreThanBatchSizeItems_queuesDrainSequentially() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        constraintsState.value = Constraints.NONE
        resetConstraintMocks()
        val policies = mutableListOf<ExistingWorkPolicy>()
        every {
            workManager.enqueueUniqueWork(
                any(),
                capture(policies),
                any<OneTimeWorkRequest>(),
            )
        } returns mockk(relaxed = true)

        repeat(6) { index ->
            val uri = Uri.parse("content://example/batch/$index")
            enqueuer.enqueue(uri, "key-$index", "file-$index")
        }

        assertEquals(6, policies.size)
        assertTrue(policies.all { it == ExistingWorkPolicy.APPEND_OR_REPLACE })
    }

    @Test
    fun isEnqueued_delegatesToRepository() = runBlocking {
        val enqueuer = createEnqueuer()
        val uri = Uri.parse("content://example/queued")
        every { uploadItemsRepository.observeQueuedOrProcessing(uri) } returns flowOf(true)

        val result = enqueuer.isEnqueued(uri).first()

        assertTrue(result)
        verify { uploadItemsRepository.observeQueuedOrProcessing(uri) }
    }

    private fun RecordingTree.assertActionLogged(
        action: String,
        predicate: (String) -> Boolean = { true },
    ) {
        val messages = logs.filter { entry ->
            entry.tag == "WorkManager" &&
                entry.message?.contains("action=$action") == true &&
                predicate(entry.message!!)
        }
        assertTrue(messages.isNotEmpty(), "Ожидалось наличие лога с action=$action")
    }

    private class RecordingTree : Timber.DebugTree() {
        val logs = mutableListOf<LogEntry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += LogEntry(priority, tag, message, t)
        }
    }

    private data class LogEntry(
        val priority: Int,
        val tag: String?,
        val message: String?,
        val throwable: Throwable?,
    )
}
