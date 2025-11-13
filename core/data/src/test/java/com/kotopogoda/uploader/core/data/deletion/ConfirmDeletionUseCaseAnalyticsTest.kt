package com.kotopogoda.uploader.core.data.deletion

import android.app.Activity
import android.content.ContentResolver
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmDeletionUseCaseAnalyticsTest {

    private lateinit var database: KotopogodaDatabase
    private lateinit var dao: DeletionItemDao
    private lateinit var repository: DeletionQueueRepository
    private lateinit var analytics: FakeDeletionAnalytics
    private lateinit var useCase: ConfirmDeletionUseCase
    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC)

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        contentResolver = context.contentResolver
        database = Room.inMemoryDatabaseBuilder(context, KotopogodaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.deletionItemDao()
        analytics = FakeDeletionAnalytics()
        repository = DeletionQueueRepository(dao, clock, analytics)
        useCase = ConfirmDeletionUseCase(
            context = context,
            contentResolver = contentResolver,
            deletionQueueRepository = repository,
            deleteRequestFactory = MediaStoreDeleteRequestFactory(),
            deletionAnalytics = analytics,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @AfterTest
    fun tearDown() {
        database.close()
    }

    @Test
    fun handleBatchResultCancelledFiresAnalyticsEvent() = runTest {
        val batch = ConfirmDeletionUseCase.DeleteBatch(
            id = "batch-123",
            index = 0,
            items = emptyList(),
            intentSender = IntentSenderWrapper(null!!),
            requiresRetryAfterApproval = false,
        )
        analytics.clear()

        useCase.handleBatchResult(batch, Activity.RESULT_CANCELED, null)

        val cancelledEvents = analytics.events.filterIsInstance<FakeDeletionAnalytics.DeletionEvent.Cancelled>()
        assertEquals(1, cancelledEvents.size)
        assertEquals("batch-123", cancelledEvents.first().batchId)
    }
}
