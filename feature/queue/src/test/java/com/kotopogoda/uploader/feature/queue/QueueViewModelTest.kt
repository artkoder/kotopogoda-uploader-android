package com.kotopogoda.uploader.feature.queue

import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.data.upload.UploadItemEntity
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueEntry
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkMetadata
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.feature.queue.R
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val queueFlow = MutableStateFlow<List<UploadQueueEntry>>(emptyList())
    private val workInfoFlow = MutableSharedFlow<List<WorkInfo>>(replay = 1).apply {
        tryEmit(emptyList())
    }

    private val repository: UploadQueueRepository = mockk()
    private val enqueuer: UploadEnqueuer = mockk(relaxed = true)
    private val summaryStarter: UploadSummaryStarter = mockk(relaxed = true)
    private val workManager: WorkManager = mockk()
    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(0L), ZoneOffset.UTC)
    private val workInfoMapper = QueueWorkInfoMapper(clock)

    init {
        every { repository.observeQueue() } returns queueFlow
        every { workManager.getWorkInfosByTagFlow(UploadTags.TAG_UPLOAD) } returns workInfoFlow
    }

    @Test
    fun uiStateCombinesWorkInfoWithQueue() = runTest(dispatcherRule.dispatcher) {
        val uriString = "file:///tmp/photo.jpg"
        val uniqueName = "upload:test-unique"

        val entity = UploadItemEntity(
            id = 42L,
            photoId = "photo-id",
            uri = uriString,
            displayName = "photo.jpg",
            size = 1_000L,
            state = UploadItemState.PROCESSING.rawValue,
            createdAt = 1L,
            updatedAt = 2L,
        )
        val entry = UploadQueueEntry(
            entity = entity,
            uri = null,
            state = UploadItemState.PROCESSING,
            lastErrorKind = null,
            lastErrorHttpCode = null,
        )

        val progress = Data.Builder()
            .putInt(UploadEnqueuer.KEY_PROGRESS, 75)
            .putLong(UploadEnqueuer.KEY_BYTES_SENT, 750L)
            .putLong(UploadEnqueuer.KEY_TOTAL_BYTES, 1_000L)
            .build()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workInfo = WorkInfo(
            id = UUID.randomUUID(),
            state = WorkInfo.State.RUNNING,
            tags = setOf(
                UploadTags.TAG_UPLOAD,
                UploadTags.uniqueTag(uniqueName),
                UploadTags.uriTag(uriString),
                UploadTags.kindTag(UploadWorkKind.UPLOAD),
            ),
            progress = progress,
            outputData = Data.EMPTY,
            runAttemptCount = 1,
            generation = 0,
            constraints = constraints,
            nextScheduleTimeMillis = clock.millis() + 30_000L,
        )

        val viewModel = QueueViewModel(
            uploadQueueRepository = repository,
            uploadEnqueuer = enqueuer,
            summaryStarter = summaryStarter,
            workManager = workManager,
            workInfoMapper = workInfoMapper,
        )

        val collectedStates = mutableListOf<QueueUiState>()
        val job = launch { viewModel.uiState.collect { collectedStates += it } }

        queueFlow.value = listOf(entry)
        workInfoFlow.emit(listOf(workInfo))
        advanceUntilIdle()

        val item = collectedStates.last { it.items.isNotEmpty() }.items.single()
        assertEquals(42L, item.id)
        assertEquals(75, item.progressPercent)
        assertEquals(750L, item.bytesSent)
        assertEquals(1_000L, item.totalBytes)
        assertEquals(true, item.isActiveTransfer)
        assertEquals(R.string.queue_status_running, item.statusResId)
        assertEquals(R.string.queue_network_connected, item.waitingReasons[0].messageResId)
        assertEquals(R.string.queue_retry_in, item.waitingReasons[1].messageResId)

        job.cancel()
    }

    @Test
    fun retryUsesStoredIdempotencyKey() = runTest(dispatcherRule.dispatcher) {
        val uri = Uri.parse("file:///tmp/retry.jpg")
        val entity = UploadItemEntity(
            id = 100L,
            photoId = "photo-retry",
            idempotencyKey = "stored-key",
            uri = uri.toString(),
            displayName = "retry.jpg",
            size = 2048L,
            state = UploadItemState.FAILED.rawValue,
            createdAt = 1L,
            updatedAt = 2L,
        )
        val entry = UploadQueueEntry(
            entity = entity,
            uri = uri,
            state = UploadItemState.FAILED,
            lastErrorKind = null,
            lastErrorHttpCode = null,
        )
        val item = entry.toQueueItemUiModel(workInfo = null)
        every { enqueuer.uniqueName(uri) } returns "unique-retry"
        coEvery { enqueuer.retry(any()) } returns Unit

        val viewModel = QueueViewModel(
            uploadQueueRepository = repository,
            uploadEnqueuer = enqueuer,
            summaryStarter = summaryStarter,
            workManager = workManager,
            workInfoMapper = workInfoMapper,
        )

        viewModel.onRetry(item)
        advanceUntilIdle()

        val metadataSlot = slot<UploadWorkMetadata>()
        coVerify { enqueuer.retry(capture(metadataSlot)) }
        assertEquals("stored-key", metadataSlot.captured.idempotencyKey)
    }
}
