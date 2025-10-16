package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadEnqueuerTest {

    private val continuation = mockk<WorkContinuation>(relaxed = true)
    private val workManager = mockk<WorkManager>(relaxed = true) {
        every { beginUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns continuation
        every { continuation.then(any<OneTimeWorkRequest>()) } returns continuation
    }
    private val summaryStarter = mockk<UploadSummaryStarter>(relaxed = true)

    @Test
    fun networkConstraints_connected_whenWifiOnlyDisabled() = runBlocking {
        val wifiOnlyFlow = MutableStateFlow(false)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, wifiOnlyFlow)

        assertNetworkTypeEventually(enqueuer, NetworkType.CONNECTED)
    }

    @Test
    fun networkConstraints_unmetered_whenWifiOnlyEnabled() = runBlocking {
        val wifiOnlyFlow = MutableStateFlow(true)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, wifiOnlyFlow)

        assertNetworkTypeEventually(enqueuer, NetworkType.UNMETERED)
    }

    @Test
    fun enqueue_multipleItems_shareQueueNameAndPolicy() {
        val wifiOnlyFlow = MutableStateFlow(false)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, wifiOnlyFlow)
        val queueNames = mutableListOf<String>()
        val policies = mutableListOf<ExistingWorkPolicy>()
        val uploads = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.beginUniqueWork(capture(queueNames), capture(policies), capture(uploads))
        } returns continuation

        enqueuer.enqueue(Uri.parse("content://example/1"), "key-1", "file-1")
        enqueuer.enqueue(Uri.parse("content://example/2"), "key-2", "file-2")

        assertEquals(listOf("upload-queue", "upload-queue"), queueNames)
        assertEquals(
            listOf(ExistingWorkPolicy.APPEND_OR_REPLACE, ExistingWorkPolicy.APPEND_OR_REPLACE),
            policies,
        )
        assertEquals(2, uploads.size)
    }

    @Test
    fun enqueue_preservesUniqueTagsForEachRequest() {
        val wifiOnlyFlow = MutableStateFlow(false)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, wifiOnlyFlow)
        val uploadRequests = mutableListOf<OneTimeWorkRequest>()
        val pollRequests = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.beginUniqueWork(any(), any(), capture(uploadRequests))
        } returns continuation
        every { continuation.then(capture(pollRequests)) } returns continuation

        val uri = Uri.parse("content://example/1")
        enqueuer.enqueue(uri, "key-1", "file-1")

        val uniqueName = enqueuer.uniqueName(uri)
        val uniqueTag = UploadTags.uniqueTag(uniqueName)

        assertTrue(uploadRequests.single().tags.contains(uniqueTag))
        assertTrue(pollRequests.single().tags.contains(uniqueTag))
    }

    @Test
    fun retry_cancelsExistingWorkByUniqueTag() {
        val wifiOnlyFlow = MutableStateFlow(false)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, wifiOnlyFlow)
        val uniqueName = "upload:abc"
        val uri = Uri.parse("content://example/1")
        val metadata = UploadWorkMetadata(
            uniqueName = uniqueName,
            uri = uri,
            displayName = "file-1",
            idempotencyKey = "key-1",
            kind = UploadWorkKind.UPLOAD,
        )

        enqueuer.retry(metadata)

        verify { workManager.cancelAllWorkByTag(UploadTags.uniqueTag(uniqueName)) }
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
