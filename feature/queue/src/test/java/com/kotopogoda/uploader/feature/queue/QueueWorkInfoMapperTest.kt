package com.kotopogoda.uploader.feature.queue

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class QueueWorkInfoMapperTest {

    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(0L), ZoneOffset.UTC)
    private val mapper = QueueWorkInfoMapper(clock)

    @Test
    fun runningPollMapsToWaitingWithoutNetworkReasons() {
        val tags = setOf(
            UploadTags.TAG_POLL,
            UploadTags.uniqueTag("unique-poll"),
            UploadTags.uriTag("file:///tmp/poll.jpg"),
            UploadTags.kindTag(UploadWorkKind.POLL)
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val workInfo = WorkInfo(
            id = UUID.randomUUID(),
            state = WorkInfo.State.RUNNING,
            tags = tags,
            progress = Data.EMPTY,
            outputData = Data.EMPTY,
            runAttemptCount = 1,
            generation = 0,
            constraints = constraints,
            nextScheduleTimeMillis = -1L,
        )

        val mapped = mapper.map(workInfo)

        assertNotNull(mapped)
        assertEquals(UploadWorkKind.POLL, mapped.kind)
        assertEquals(R.string.queue_status_poll_waiting, mapped.statusResId)
        assertTrue(mapped.waitingReasons.isEmpty())
        assertFalse(mapped.isActiveTransfer)
    }

    @Test
    fun pollSucceededAwaitingDeleteUsesManualDeleteStatus() {
        val tags = setOf(
            UploadTags.TAG_POLL,
            UploadTags.uniqueTag("unique-poll"),
            UploadTags.uriTag("file:///tmp/poll.jpg"),
            UploadTags.kindTag(UploadWorkKind.POLL)
        )
        val progress = Data.Builder()
            .putString(UploadEnqueuer.KEY_COMPLETION_STATE, UploadEnqueuer.STATE_UPLOADED_AWAITING_DELETE)
            .build()
        val output = Data.Builder()
            .putString(UploadEnqueuer.KEY_COMPLETION_STATE, UploadEnqueuer.STATE_UPLOADED_AWAITING_DELETE)
            .build()
        val workInfo = WorkInfo(
            id = UUID.randomUUID(),
            state = WorkInfo.State.SUCCEEDED,
            tags = tags,
            progress = progress,
            outputData = output,
            runAttemptCount = 1,
            generation = 0,
            constraints = Constraints.NONE,
            nextScheduleTimeMillis = -1L,
        )

        val mapped = mapper.map(workInfo)

        assertNotNull(mapped)
        assertEquals(UploadWorkKind.POLL, mapped.kind)
        assertEquals(R.string.queue_status_poll_manual_delete, mapped.statusResId)
        assertTrue(mapped.waitingReasons.isEmpty())
    }

    @Test
    fun runningUploadIncludesProgressAndWaitingReasons() {
        val tags = setOf(
            UploadTags.TAG_UPLOAD,
            UploadTags.uniqueTag("unique"),
            UploadTags.uriTag("file:///tmp/photo.jpg"),
            UploadTags.kindTag(UploadWorkKind.UPLOAD)
        )
        val progress = Data.Builder()
            .putInt(UploadEnqueuer.KEY_PROGRESS, 50)
            .putLong(UploadEnqueuer.KEY_BYTES_SENT, 500L)
            .putLong(UploadEnqueuer.KEY_TOTAL_BYTES, 1_000L)
            .build()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val workInfo = WorkInfo(
            id = UUID.randomUUID(),
            state = WorkInfo.State.RUNNING,
            tags = tags,
            progress = progress,
            outputData = Data.EMPTY,
            runAttemptCount = 1,
            generation = 0,
            constraints = constraints,
            nextScheduleTimeMillis = clock.millis() + 60_000L,
        )

        val mapped = mapper.map(workInfo)

        assertNotNull(mapped)
        assertEquals(UploadWorkKind.UPLOAD, mapped.kind)
        assertEquals(50, mapped.progressPercent)
        assertEquals(500L, mapped.bytesSent)
        assertEquals(1_000L, mapped.totalBytes)
        assertEquals(R.string.queue_status_running, mapped.statusResId)
        assertTrue(mapped.isActiveTransfer)
        val waitingReasons = mapped.waitingReasons
        assertEquals(1, waitingReasons.size)
        val reason = waitingReasons.single()
        assertEquals(R.string.queue_retry_in, reason.messageResId)
        assertEquals(listOf("01:00"), reason.formatArgs)
    }

    @Test
    fun enqueuedBackoffShowsRetryWithoutNetworkReason() {
        val tags = setOf(
            UploadTags.TAG_UPLOAD,
            UploadTags.uniqueTag("unique"),
            UploadTags.uriTag("file:///tmp/photo.jpg"),
            UploadTags.kindTag(UploadWorkKind.UPLOAD)
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workInfo = WorkInfo(
            id = UUID.randomUUID(),
            state = WorkInfo.State.ENQUEUED,
            tags = tags,
            progress = Data.EMPTY,
            outputData = Data.EMPTY,
            runAttemptCount = 2,
            generation = 0,
            constraints = constraints,
            nextScheduleTimeMillis = clock.millis() + 30_000L,
        )

        val mapped = mapper.map(workInfo)

        assertNotNull(mapped)
        assertEquals(1, mapped.waitingReasons.size)
        val reason = mapped.waitingReasons.single()
        assertEquals(R.string.queue_retry_in, reason.messageResId)
        assertEquals(listOf("30\u202fс"), reason.formatArgs)
    }

    @Test
    fun retryDelayIsClampedToTwentyFourHours() {
        val tags = setOf(
            UploadTags.TAG_UPLOAD,
            UploadTags.uniqueTag("unique"),
            UploadTags.uriTag("file:///tmp/photo.jpg"),
            UploadTags.kindTag(UploadWorkKind.UPLOAD)
        )
        val workInfo = WorkInfo(
            id = UUID.randomUUID(),
            state = WorkInfo.State.ENQUEUED,
            tags = tags,
            progress = Data.EMPTY,
            outputData = Data.EMPTY,
            runAttemptCount = 2,
            generation = 0,
            constraints = Constraints.NONE,
            nextScheduleTimeMillis = clock.millis() + TimeUnit.DAYS.toMillis(90),
        )

        val mapped = mapper.map(workInfo)

        assertNotNull(mapped)
        val reason = mapped.waitingReasons.single()
        assertEquals(R.string.queue_retry_in, reason.messageResId)
        assertEquals(listOf("24:00:00"), reason.formatArgs)
    }
}
