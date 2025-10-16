package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkRequest
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadEnqueuerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val summaryStarter = mockk<UploadSummaryStarter>(relaxed = true)
    private val repository = mockk<com.kotopogoda.uploader.core.data.upload.UploadItemsRepository>(relaxed = true)

    @Test
    fun networkConstraints_connected_whenWifiOnlyDisabled() = runBlocking {
        val wifiOnlyFlow = MutableStateFlow(false)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, repository, wifiOnlyFlow)

        assertNetworkTypeEventually(enqueuer, NetworkType.CONNECTED)
    }

    @Test
    fun networkConstraints_unmetered_whenWifiOnlyEnabled() = runBlocking {
        val wifiOnlyFlow = MutableStateFlow(true)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, repository, wifiOnlyFlow)

        assertNetworkTypeEventually(enqueuer, NetworkType.UNMETERED)
    }

    @Test
    fun enqueue_recordsItemAndStartsWorker() = runBlocking {
        clearMocks(workManager, repository)
        val wifiOnlyFlow = MutableStateFlow(false)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, repository, wifiOnlyFlow)
        val queueNameSlot = slot<String>()
        val policySlot = slot<ExistingWorkPolicy>()
        val requestSlot = slot<WorkRequest>()
        every {
            workManager.enqueueUniqueWork(capture(queueNameSlot), capture(policySlot), capture(requestSlot))
        } returns mockk(relaxed = true)

        val uri = Uri.parse("content://example/1")
        enqueuer.enqueue(uri, "key-1", "file-1")

        val uniqueName = enqueuer.uniqueName(uri)
        coVerify(timeout = 1_000) {
            repository.upsertPending(uniqueName, uri, "key-1", "file-1")
        }
        verify(timeout = 1_000) {
            workManager.enqueueUniqueWork(any(), ExistingWorkPolicy.KEEP, any())
        }
        assertEquals("upload-queue", queueNameSlot.captured)
        assertEquals(ExistingWorkPolicy.KEEP, policySlot.captured)
    }

    @Test
    fun retry_marksItemPending_andRestartsWorker() = runBlocking {
        clearMocks(workManager, repository)
        val wifiOnlyFlow = MutableStateFlow(false)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, repository, wifiOnlyFlow)
        every { workManager.enqueueUniqueWork(any(), any(), any()) } returns mockk(relaxed = true)

        val metadata = UploadWorkMetadata(
            uniqueName = "upload:abc",
            uri = Uri.parse("content://example/1"),
            displayName = "file-1",
            idempotencyKey = "key-1",
            kind = UploadWorkKind.UPLOAD,
        )

        enqueuer.retry(metadata)

        coVerify(timeout = 1_000) { repository.markPending("upload:abc") }
        verify(timeout = 1_000) { workManager.enqueueUniqueWork(any(), ExistingWorkPolicy.KEEP, any()) }
    }

    @Test
    fun cancel_marksItemCancelled_andRestartsWorker() = runBlocking {
        clearMocks(workManager, repository)
        val wifiOnlyFlow = MutableStateFlow(false)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, repository, wifiOnlyFlow)
        every { workManager.enqueueUniqueWork(any(), any(), any()) } returns mockk(relaxed = true)

        enqueuer.cancel("upload:xyz")

        coVerify(timeout = 1_000) { repository.markCancelled("upload:xyz") }
        verify(timeout = 1_000) { workManager.enqueueUniqueWork(any(), ExistingWorkPolicy.KEEP, any()) }
    }

    private suspend fun assertNetworkTypeEventually(
        enqueuer: UploadEnqueuer,
        expected: NetworkType,
    ) {
        withTimeout(TimeUnit.SECONDS.toMillis(1)) {
            while (true) {
                val constraints = enqueuer.networkConstraints()
                if (constraints.requiredNetworkType == expected) {
                    return@withTimeout
                }
                delay(10)
            }
        }
        throw AssertionError("Expected network type $expected")
    }
}
