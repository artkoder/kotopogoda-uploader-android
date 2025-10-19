package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
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
    fun scheduleDrain_enqueuesConnectedConstraintsWithExpeditedWork() = runBlocking {
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
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, request.workSpec.outOfQuotaPolicy)
        assertEquals(listOf(ExistingWorkPolicy.APPEND_OR_REPLACE), policies)
    }

    @Test
    fun scheduleDrain_buildsConstraintsWhenStateEmpty() {
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

        verify { constraintsProvider.buildConstraints() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                requestSlot.captured,
            )
        }

        logTree.assertActionLogged(
            action = "drain_worker_constraints_missing",
            predicate = { it.contains("source=enqueuer") },
        )
        logTree.assertActionLogged(
            action = "drain_worker_constraints_built",
            predicate = { it.contains("source=enqueuer") },
        )
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
                match { it.workSpec.constraints == Constraints.NONE }
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
        verify { constraintsProvider.shouldUseExpeditedWork() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                match { it.workSpec.constraints == Constraints.NONE }
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
        verify { constraintsProvider.shouldUseExpeditedWork() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                match { it.workSpec.constraints == Constraints.NONE }
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
        verify { constraintsProvider.shouldUseExpeditedWork() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                match { it.workSpec.constraints == Constraints.NONE }
            )
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME) }
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
        verify { constraintsProvider.shouldUseExpeditedWork() }
        verify {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                match { it.workSpec.constraints == Constraints.NONE }
            )
        }
    }

    @Test
    fun scheduleDrain_setsExpeditedWhenEnabled() {
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
        assertTrue(requestSlot.captured.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, requestSlot.captured.workSpec.outOfQuotaPolicy)
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
