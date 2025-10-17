package com.kotopogoda.uploader.upload

import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UploadStartupInitializerTest {

    private val uploadQueueRepository = mockk<UploadQueueRepository>()
    private val uploadEnqueuer = mockk<UploadEnqueuer>(relaxed = true)
    private val summaryStarter = mockk<UploadSummaryStarter>(relaxed = true)

    private val initializer = UploadStartupInitializer(
        uploadQueueRepository = uploadQueueRepository,
        uploadEnqueuer = uploadEnqueuer,
        summaryStarter = summaryStarter,
    )

    @Test
    fun `does nothing when queue is empty`() = runTest {
        coEvery { uploadQueueRepository.hasQueued() } returns false

        initializer.ensureUploadRunningIfQueued()

        coVerify(exactly = 1) { uploadQueueRepository.hasQueued() }
        verify(exactly = 0) { uploadEnqueuer.ensureUploadRunning() }
        verify(exactly = 0) { summaryStarter.ensureRunning() }
    }

    @Test
    fun `starts upload when queue has items`() = runTest {
        coEvery { uploadQueueRepository.hasQueued() } returns true

        initializer.ensureUploadRunningIfQueued()

        coVerify(exactly = 1) { uploadQueueRepository.hasQueued() }
        verify(exactly = 1) { uploadEnqueuer.ensureUploadRunning() }
        verify(exactly = 1) { summaryStarter.ensureRunning() }
    }
}
