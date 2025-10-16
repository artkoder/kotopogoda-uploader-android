package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
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
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

class UploadEnqueuerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val summaryStarter = mockk<UploadSummaryStarter>(relaxed = true)
    private val uploadItemsRepository = mockk<UploadQueueRepository>(relaxed = true)
    private val constraintsProvider = mockk<UploadConstraintsProvider>()

    init {
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)
        every { workManager.cancelUniqueWork(any()) } returns mockk(relaxed = true)
        every { constraintsProvider.buildConstraints() } returns Constraints.NONE
    }

    private fun createEnqueuer(): UploadEnqueuer = UploadEnqueuer(
        workManager = workManager,
        summaryStarter = summaryStarter,
        uploadItemsRepository = uploadItemsRepository,
        constraintsProvider = constraintsProvider,
    )

    @Test
    fun enqueue_persistsItemAndStartsWorker() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        val uri = Uri.parse("content://example/1")

        enqueuer.enqueue(uri, "key-1", "file-1")

        coVerify { uploadItemsRepository.enqueue(uri) }
        verify { summaryStarter.ensureRunning() }
        verify { constraintsProvider.buildConstraints() }
        verify {
            workManager.enqueueUniqueWork(
                UPLOAD_PROCESSOR_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                match { it.workSpec.constraints == Constraints.NONE }
            )
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(UPLOAD_PROCESSOR_WORK_NAME) }
    }

    @Test
    fun cancel_cancelsProcessorWhenItemProcessing() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        val uri = Uri.parse("content://example/2")
        coEvery { uploadItemsRepository.markCancelled(uri) } returns true

        enqueuer.cancel(uri)

        val uniqueTag = UploadTags.uniqueTag(enqueuer.uniqueName(uri))
        verify { workManager.cancelAllWorkByTag(uniqueTag) }
        verify { workManager.cancelUniqueWork(UPLOAD_PROCESSOR_WORK_NAME) }
        coVerify { uploadItemsRepository.markCancelled(uri) }
        verify { constraintsProvider.buildConstraints() }
        verify {
            workManager.enqueueUniqueWork(
                UPLOAD_PROCESSOR_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                match { it.workSpec.constraints == Constraints.NONE }
            )
        }
    }

    @Test
    fun cancel_skipsProcessorCancellationWhenItemNotProcessing() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
        val uri = Uri.parse("content://example/20")
        coEvery { uploadItemsRepository.markCancelled(uri) } returns false

        enqueuer.cancel(uri)

        val uniqueTag = UploadTags.uniqueTag(enqueuer.uniqueName(uri))
        verify { workManager.cancelAllWorkByTag(uniqueTag) }
        verify(exactly = 0) { workManager.cancelUniqueWork(UPLOAD_PROCESSOR_WORK_NAME) }
        coVerify { uploadItemsRepository.markCancelled(uri) }
        verify { constraintsProvider.buildConstraints() }
        verify {
            workManager.enqueueUniqueWork(
                UPLOAD_PROCESSOR_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                match { it.workSpec.constraints == Constraints.NONE }
            )
        }
    }

    @Test
    fun retry_requeuesItemAndStartsWorker() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)
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
        coVerify { uploadItemsRepository.enqueue(uri) }
        verify { summaryStarter.ensureRunning() }
        verify { constraintsProvider.buildConstraints() }
        verify {
            workManager.enqueueUniqueWork(
                UPLOAD_PROCESSOR_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                match { it.workSpec.constraints == Constraints.NONE }
            )
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(UPLOAD_PROCESSOR_WORK_NAME) }
    }

    @Test
    fun cancelAllUploads_cancelsTagsAndUpdatesRepository() = runBlocking {
        val enqueuer = createEnqueuer()
        clearMocks(workManager, constraintsProvider, answers = false)

        enqueuer.cancelAllUploads()

        verify { workManager.cancelAllWorkByTag(UploadTags.TAG_UPLOAD) }
        verify { workManager.cancelAllWorkByTag(UploadTags.TAG_POLL) }
        verify { workManager.cancelUniqueWork(UPLOAD_PROCESSOR_WORK_NAME) }
        coVerify { uploadItemsRepository.cancelAll() }
        verify { constraintsProvider.buildConstraints() }
        verify {
            workManager.enqueueUniqueWork(
                UPLOAD_PROCESSOR_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                match { it.workSpec.constraints == Constraints.NONE }
            )
        }
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
