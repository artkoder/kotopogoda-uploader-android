package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkMetadata
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UploadEnqueuerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val summaryStarter = mockk<UploadSummaryStarter>(relaxed = true)
    private val uploadItemsRepository = mockk<UploadQueueRepository>(relaxed = true)

    init {
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)
    }

    private fun createEnqueuer(): UploadEnqueuer = UploadEnqueuer(
        workManager = workManager,
        summaryStarter = summaryStarter,
        uploadItemsRepository = uploadItemsRepository,
    )

    @Test
    fun enqueue_persistsItemAndStartsWorker() = runBlocking {
        val enqueuer = createEnqueuer()
        val uri = Uri.parse("content://example/1")

        enqueuer.enqueue(uri, "key-1", "file-1")

        coVerify { uploadItemsRepository.enqueue(uri) }
        verify { summaryStarter.ensureRunning() }
        verify {
            workManager.enqueueUniqueWork(
                UPLOAD_QUEUE_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun cancel_cancelsWorkAndUpdatesRepository() = runBlocking {
        val enqueuer = createEnqueuer()
        val uri = Uri.parse("content://example/2")

        enqueuer.cancel(uri)

        val uniqueTag = UploadTags.uniqueTag(enqueuer.uniqueName(uri))
        verify { workManager.cancelAllWorkByTag(uniqueTag) }
        coVerify { uploadItemsRepository.markCancelled(uri) }
        verify {
            workManager.enqueueUniqueWork(
                UPLOAD_QUEUE_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun retry_requeuesItemAndStartsWorker() = runBlocking {
        val enqueuer = createEnqueuer()
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
        verify {
            workManager.enqueueUniqueWork(
                UPLOAD_QUEUE_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun cancelAllUploads_cancelsTagsAndUpdatesRepository() = runBlocking {
        val enqueuer = createEnqueuer()

        enqueuer.cancelAllUploads()

        verify { workManager.cancelAllWorkByTag(UploadTags.TAG_UPLOAD) }
        verify { workManager.cancelAllWorkByTag(UploadTags.TAG_POLL) }
        coVerify { uploadItemsRepository.cancelAll() }
        verify {
            workManager.enqueueUniqueWork(
                UPLOAD_QUEUE_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>()
            )
        }
    }
}
