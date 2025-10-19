package com.kotopogoda.uploader.core.network.upload

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.data.upload.UploadQueueItem
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
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
        coEvery { constraintsProvider.awaitConstraints() } answers { constraintsState.value }
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
            workManager,
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
            workManager,
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
            workManager,
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
    fun `worker retries when constraints not yet available`() = runTest {
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
        coEvery { constraintsProvider.awaitConstraints() } returns null

        val worker = QueueDrainWorker(
            context,
            workerParams,
            repository,
            workManager,
            constraintsProvider,
        )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) { repository.markProcessing(any()) }
        verify(exactly = 0) {
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
            workManager,
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
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any(),
            )
        }

        logTree.assertActionLogged("drain_worker_reschedule")
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
            workManager,
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
        val drainPolicy = policies[names.indexOf(QUEUE_DRAIN_WORK_NAME)]

        assertTrue(uploadRequest.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, uploadRequest.workSpec.outOfQuotaPolicy)
        assertTrue(drainRequest.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, drainRequest.workSpec.outOfQuotaPolicy)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, drainPolicy)

        logTree.assertActionLogged(
            action = "drain_worker_reschedule",
            predicate = { it.contains("expedited=true") },
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
