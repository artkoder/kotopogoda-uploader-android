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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadEnqueuerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val summaryStarter = mockk<UploadSummaryStarter>(relaxed = true)
    private val uploadItemsRepository = mockk<UploadQueueRepository>(relaxed = true)
    private val constraintsProvider = mockk<UploadConstraintsProvider>()
    private val wifiOnlyState = MutableStateFlow<Boolean?>(true)
    private val constraintsState = MutableStateFlow<Constraints?>(Constraints.NONE)

    init {
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)
        every { workManager.cancelUniqueWork(any()) } returns mockk(relaxed = true)
        resetConstraintMocks()
    }

    private fun resetConstraintMocks() {
        every { constraintsProvider.wifiOnlyUploadsState } returns wifiOnlyState
        every { constraintsProvider.constraintsState } returns constraintsState
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.NONE }
        every { constraintsProvider.shouldUseExpeditedWork() } answers { wifiOnlyState.value?.not() ?: false }
    }

    private fun createEnqueuer(): UploadEnqueuer = UploadEnqueuer(
        workManager = workManager,
        summaryStarter = summaryStarter,
        uploadItemsRepository = uploadItemsRepository,
        constraintsProvider = constraintsProvider,
    )

    @Test
    fun scheduleDrain_waitsForPreferenceAndReplacesWifiWork() = runBlocking {
        val wifiOnlyFlow = MutableSharedFlow<Boolean>()
        val constraintsHelper = UploadConstraintsHelper(wifiOnlyFlow)
        val enqueuer = UploadEnqueuer(
            workManager = workManager,
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

        verify(exactly = 0) { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }

        wifiOnlyFlow.emit(false)

        verify(timeout = 1_000L) {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }

        val request = requests.single()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertFalse(request.workSpec.expedited)
        assertEquals(listOf(ExistingWorkPolicy.REPLACE), policies)
    }

    @Test
    fun wifiPreferenceSwitchToMobile_cancelsActiveWorkAndRequeuesProcessing() = runBlocking {
        val enqueuer = createEnqueuer()
        val wifiConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val mobileConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        wifiOnlyState.value = true
        constraintsState.value = wifiConstraints
        resetConstraintMocks()
        enqueuer.scheduleDrain()

        clearMocks(workManager, uploadItemsRepository, constraintsProvider, answers = false)
        constraintsState.value = mobileConstraints
        resetConstraintMocks()
        val policies = mutableListOf<ExistingWorkPolicy>()
        val requests = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                any(),
                capture(policies),
                capture(requests),
            )
        } returns mockk(relaxed = true)

        wifiOnlyState.value = false

        verify(timeout = 1_000) {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                any(),
                any<OneTimeWorkRequest>(),
            )
        }
        verify(timeout = 1_000) { workManager.cancelAllWorkByTag(UploadTags.TAG_UPLOAD) }
        verify(timeout = 1_000) { workManager.cancelAllWorkByTag(UploadTags.TAG_POLL) }
        coVerify(timeout = 1_000) { uploadItemsRepository.requeueAllProcessing() }
        assertTrue(policies.isNotEmpty())
        val request = requests.last()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.expedited)
        assertEquals(ExistingWorkPolicy.REPLACE, policies.last())
    }

    @Test
    fun wifiPreferenceSwitchToWifi_cancelsActiveWorkAndRequeuesProcessing() = runBlocking {
        val enqueuer = createEnqueuer()
        val mobileConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val wifiConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        wifiOnlyState.value = false
        constraintsState.value = mobileConstraints
        resetConstraintMocks()
        enqueuer.scheduleDrain()

        clearMocks(workManager, uploadItemsRepository, constraintsProvider, answers = false)
        constraintsState.value = wifiConstraints
        resetConstraintMocks()
        val policies = mutableListOf<ExistingWorkPolicy>()
        val requests = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                any(),
                capture(policies),
                capture(requests),
            )
        } returns mockk(relaxed = true)

        wifiOnlyState.value = true

        verify(timeout = 1_000) {
            workManager.enqueueUniqueWork(
                QUEUE_DRAIN_WORK_NAME,
                any(),
                any<OneTimeWorkRequest>(),
            )
        }
        verify(timeout = 1_000) { workManager.cancelAllWorkByTag(UploadTags.TAG_UPLOAD) }
        verify(timeout = 1_000) { workManager.cancelAllWorkByTag(UploadTags.TAG_POLL) }
        coVerify(timeout = 1_000) { uploadItemsRepository.requeueAllProcessing() }
        assertTrue(policies.isNotEmpty())
        val request = requests.last()
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertFalse(request.workSpec.expedited)
        assertEquals(ExistingWorkPolicy.REPLACE, policies.last())
    }

    @Test
    fun enqueue_persistsItemAndStartsWorker() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        wifiOnlyState.value = true
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
        wifiOnlyState.value = true
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
        wifiOnlyState.value = true
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
        wifiOnlyState.value = true
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
        wifiOnlyState.value = true
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
        wifiOnlyState.value = true
        constraintsState.value = Constraints.NONE
        resetConstraintMocks()
        wifiOnlyState.value = false
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
        wifiOnlyState.value = true
    }

    @Test
    fun enqueue_moreThanBatchSizeItems_queuesDrainSequentially() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        wifiOnlyState.value = true
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
}
