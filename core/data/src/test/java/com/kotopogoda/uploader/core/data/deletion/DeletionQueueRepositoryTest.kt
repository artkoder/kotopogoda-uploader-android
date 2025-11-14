package com.kotopogoda.uploader.core.data.deletion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kotopogoda.uploader.core.data.database.KotopogodaDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletionQueueRepositoryTest {

    private lateinit var database: KotopogodaDatabase
    private lateinit var dao: DeletionItemDao
    private lateinit var repository: DeletionQueueRepository
    private lateinit var analytics: FakeDeletionAnalytics
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

    @BeforeTest
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KotopogodaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.deletionItemDao()
        analytics = FakeDeletionAnalytics()
        repository = DeletionQueueRepository(dao, clock, analytics)
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun observePendingFiltersUploadingAndStatus() = runTest {
        val baseItem = DeletionItem(
            mediaId = 1L,
            contentUri = "content://photos/1",
            displayName = "photo-1.jpg",
            sizeBytes = 1024L,
            dateTaken = 1000L,
            reason = "user_delete",
            status = DeletionItemStatus.PENDING,
            isUploading = false,
            createdAt = 10L,
        )
        val uploading = baseItem.copy(mediaId = 2L, isUploading = true, createdAt = 11L)
        val confirmed = baseItem.copy(mediaId = 3L, status = DeletionItemStatus.CONFIRMED, createdAt = 12L)
        dao.enqueue(listOf(baseItem, uploading, confirmed))

        val pendingFlow = repository.observePending().first()
        val pendingDirect = repository.getPending()

        assertEquals(listOf(baseItem), pendingFlow)
        assertEquals(listOf(baseItem), pendingDirect)
    }

    @Test
    fun enqueueResetsStatusAndSetsTimestamp() = runTest {
        val requests = listOf(
            DeletionRequest(
                mediaId = 10L,
                contentUri = "content://photos/10",
                displayName = "photo-10.jpg",
                sizeBytes = 2048L,
                dateTaken = 2000L,
                reason = "user_delete",
            ),
            DeletionRequest(
                mediaId = 11L,
                contentUri = "content://photos/11",
                displayName = null,
                sizeBytes = null,
                dateTaken = null,
                reason = "uploaded_cleanup",
            ),
        )

        val inserted = repository.enqueue(requests)

        assertEquals(2, inserted)
        val stored = dao.getByIds(listOf(10L, 11L)).sortedBy { it.mediaId }
        assertEquals(2, stored.size)
        val baseTime = clock.millis()
        val first = stored[0]
        val second = stored[1]
        assertEquals(DeletionItemStatus.PENDING, first.status)
        assertEquals(false, first.isUploading)
        assertEquals(baseTime, first.createdAt)
        assertEquals(baseTime + 1, second.createdAt)
        assertEquals(DeletionItemStatus.PENDING, second.status)
        assertEquals(false, second.isUploading)
    }

    @Test
    fun enqueueSkipsExistingPendingItems() = runTest {
        val existing = DeletionItem(
            mediaId = 42L,
            contentUri = "content://photos/42",
            displayName = "photo-42.jpg",
            sizeBytes = 4096L,
            dateTaken = 3000L,
            reason = "user_delete",
            status = DeletionItemStatus.PENDING,
            isUploading = false,
            createdAt = 123L,
        )
        dao.enqueue(listOf(existing))

        val requests = listOf(
            DeletionRequest(
                mediaId = 42L,
                contentUri = "content://photos/42",
                displayName = "photo-42.jpg",
                sizeBytes = 4096L,
                dateTaken = 3000L,
                reason = "uploaded_cleanup",
            ),
            DeletionRequest(
                mediaId = 43L,
                contentUri = "content://photos/43",
                displayName = "photo-43.jpg",
                sizeBytes = 5120L,
                dateTaken = 4000L,
                reason = "uploaded_cleanup",
            ),
        )

        val inserted = repository.enqueue(requests)

        assertEquals(1, inserted)
        val stored = dao.getByIds(listOf(42L, 43L)).associateBy { it.mediaId }
        val original = stored.getValue(42L)
        assertEquals(123L, original.createdAt)
        assertEquals("user_delete", original.reason)
        val newlyQueued = stored.getValue(43L)
        assertEquals(DeletionItemStatus.PENDING, newlyQueued.status)
        assertEquals(clock.millis(), newlyQueued.createdAt)
        assertEquals("uploaded_cleanup", newlyQueued.reason)

        val repeated = repository.enqueue(requests)
        assertEquals(0, repeated)

        val events = analytics.events.filterIsInstance<FakeDeletionAnalytics.DeletionEvent.Enqueued>()
        assertEquals(1, events.size)
        assertEquals(1, events.first().count)
        assertEquals("uploaded_cleanup", events.first().reason)
    }

    @Test
    fun statusTransitionsUpdateEntities() = runTest {
        val items = listOf(1L, 2L, 3L).mapIndexed { index, id ->
            DeletionItem(
                mediaId = id,
                contentUri = "content://photos/$id",
                displayName = "photo-$id.jpg",
                sizeBytes = 100L * (index + 1),
                dateTaken = 1000L + index,
                reason = "user_delete",
                createdAt = 50L + index,
            )
        }
        dao.enqueue(items)

        val confirmed = repository.markConfirmed(listOf(1L))
        val failed = repository.markFailed(listOf(2L), cause = "io_error")
        val skipped = repository.markSkipped(listOf(3L))

        assertEquals(1, confirmed)
        assertEquals(1, failed)
        assertEquals(1, skipped)

        val updated = dao.getByIds(listOf(1L, 2L, 3L)).associateBy { it.mediaId }
        assertEquals(DeletionItemStatus.CONFIRMED, updated.getValue(1L).status)
        assertEquals(false, updated.getValue(1L).isUploading)
        assertEquals(DeletionItemStatus.FAILED, updated.getValue(2L).status)
        assertEquals(DeletionItemStatus.SKIPPED, updated.getValue(3L).status)
    }

    @Test
    fun markUploadingAffectsPendingItemsOnly() = runTest {
        val pending = DeletionItem(
            mediaId = 100L,
            contentUri = "content://photos/100",
            displayName = null,
            sizeBytes = null,
            dateTaken = null,
            reason = "user_delete",
            createdAt = 1L,
        )
        val failed = pending.copy(mediaId = 101L, status = DeletionItemStatus.FAILED)
        dao.enqueue(listOf(pending, failed))

        val updated = repository.markUploading(listOf(100L, 101L), uploading = true)

        assertEquals(1, updated)
        val stored = dao.getByIds(listOf(100L, 101L)).associateBy { it.mediaId }
        assertTrue(stored.getValue(100L).isUploading)
        assertTrue(!stored.getValue(101L).isUploading)
    }

    @Test
    fun enqueueFiresAnalyticsEvent() = runTest {
        val requests = listOf(
            DeletionRequest(
                mediaId = 50L,
                contentUri = "content://photos/50",
                displayName = "photo-50.jpg",
                sizeBytes = 1024L,
                dateTaken = 5000L,
                reason = "user_delete",
            ),
        )

        val inserted = repository.enqueue(requests)

        assertEquals(1, inserted)
        assertEquals(1, analytics.events.size)
        val event = analytics.events.first()
        assertTrue(event is FakeDeletionAnalytics.DeletionEvent.Enqueued)
        assertEquals(1, (event as FakeDeletionAnalytics.DeletionEvent.Enqueued).count)
        assertEquals("user_delete", event.reason)
    }

    @Test
    fun markFailedFiresAnalyticsEvent() = runTest {
        val item = DeletionItem(
            mediaId = 60L,
            contentUri = "content://photos/60",
            displayName = "photo-60.jpg",
            sizeBytes = 2048L,
            dateTaken = 6000L,
            reason = "user_delete",
            createdAt = 100L,
        )
        dao.enqueue(listOf(item))
        analytics.clear()

        repository.markFailed(listOf(60L), cause = "permission_denied")

        assertEquals(1, analytics.events.size)
        val event = analytics.events.first()
        assertTrue(event is FakeDeletionAnalytics.DeletionEvent.Failed)
        assertEquals(1, (event as FakeDeletionAnalytics.DeletionEvent.Failed).count)
        assertEquals("permission_denied", event.cause)
    }

    @Test
    fun purgeRemovesTerminalRecordsOlderThanThreshold() = runTest {
        val baseTime = clock.millis()
        val items = listOf(
            DeletionItem(
                mediaId = 201L,
                contentUri = "content://photos/201",
                displayName = "photo-201.jpg",
                sizeBytes = 1_024L,
                dateTaken = 10_000L,
                reason = "user_delete",
                status = DeletionItemStatus.CONFIRMED,
                createdAt = baseTime - DEFAULT_RETENTION_DELTA,
            ),
            DeletionItem(
                mediaId = 202L,
                contentUri = "content://photos/202",
                displayName = "photo-202.jpg",
                sizeBytes = 2_048L,
                dateTaken = 11_000L,
                reason = "user_delete",
                status = DeletionItemStatus.SKIPPED,
                createdAt = baseTime - DEFAULT_RETENTION_DELTA,
            ),
            DeletionItem(
                mediaId = 203L,
                contentUri = "content://photos/203",
                displayName = "photo-203.jpg",
                sizeBytes = 3_072L,
                dateTaken = 12_000L,
                reason = "user_delete",
                status = DeletionItemStatus.FAILED,
                createdAt = baseTime - 1000L,
            ),
            DeletionItem(
                mediaId = 204L,
                contentUri = "content://photos/204",
                displayName = "photo-204.jpg",
                sizeBytes = 4_096L,
                dateTaken = 13_000L,
                reason = "user_delete",
                status = DeletionItemStatus.CONFIRMED,
                createdAt = baseTime,
            ),
        )
        dao.enqueue(items)

        val removed = repository.purge(olderThan = baseTime - 10L)

        assertEquals(2, removed)
        val remainingIds = dao.getAll().map { it.mediaId }.sorted()
        assertEquals(listOf(203L, 204L), remainingIds)
    }

    private companion object {
        private const val DEFAULT_RETENTION_DELTA = 7 * 24 * 60 * 60 * 1000L + 1L
    }
}
