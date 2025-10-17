package com.kotopogoda.uploader.upload

import com.kotopogода.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UploadStartupInitializerTest {

    private val uploadQueueRepository = mockk<UploadQueueRepository>()
    private val uploadEnqueuer = mockk<UploadEnqueuer>(relaxed = true)
    private val uploadSummaryStarter = mockk<UploadSummaryStarter>(relaxed = true)
    private val initializer = UploadStartupInitializer(
        uploadQueueRepository = uploadQueueRepository,
        uploadEnqueuer = uploadEnqueuer,
        uploadSummaryStarter = uploadSummaryStarter,
    )

    @Test
    fun `ensureRunningIfNeeded запускает воркеры при наличии задач`() = runTest {
        coEvery { uploadQueueRepository.hasQueued() } returns true

        initializer.ensureRunningIfNeeded()

        coVerify(exactly = 1) { uploadQueueRepository.hasQueued() }
        verify(exactly = 1) { uploadSummaryStarter.ensureRunning() }
        verify(exactly = 1) { uploadEnqueuer.ensureUploadRunning() }
    }

    @Test
    fun `ensureRunningIfNeeded ничего не делает при пустой очереди`() = runTest {
        coEvery { uploadQueueRepository.hasQueued() } returns false

        initializer.ensureRunningIfNeeded()

        coVerify(exactly = 1) { uploadQueueRepository.hasQueued() }
        verify(exactly = 0) { uploadSummaryStarter.ensureRunning() }
        verify(exactly = 0) { uploadEnqueuer.ensureUploadRunning() }
    }
}
