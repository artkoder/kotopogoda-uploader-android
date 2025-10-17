package com.kotopogoda.uploader.feature.queue

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.feature.queue.R
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test
class QueueWorkInfoMapperTest {

    private val clock: Clock = Clock.fixed(Instant.ofEpochMilli(0L), ZoneOffset.UTC)
    private val mapper = QueueWorkInfoMapper(clock)

    @Test
    fun mapReturnsNullForNonUploadKind() {
        val workInfo = createWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(
                UploadTags.TAG_UPLOAD,
                UploadTags.kindTag(UploadWorkKind.POLL)
            )
        )

        val mapped = mapper.map(workInfo)

        assertNull(mapped)
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
        assertEquals(50, mapped.progressPercent)
        assertEquals(500L, mapped.bytesSent)
        assertEquals(1_000L, mapped.totalBytes)
        assertEquals(R.string.queue_status_running, mapped.statusResId)
        assertEquals(true, mapped.isActiveTransfer)
        val waitingReasons = mapped.waitingReasons
        assertEquals(R.string.queue_network_unmetered, waitingReasons[0].messageResId)
        assertEquals(R.string.queue_retry_in, waitingReasons[1].messageResId)
        assertEquals(listOf("01:00"), waitingReasons[1].formatArgs)
    }

    private fun createWorkInfo(
        state: WorkInfo.State,
        tags: Set<String>,
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
        constraints: Constraints = Constraints.NONE,
        nextScheduleTimeMillis: Long = -1L,
    ): WorkInfo {
        return WorkInfo(
            id = UUID.randomUUID(),
            state = state,
            tags = tags,
            progress = progress,
            outputData = output,
            runAttemptCount = 0,
            generation = 0,
            constraints = constraints,
            nextScheduleTimeMillis = nextScheduleTimeMillis,
        )
    }
}
