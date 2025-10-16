package com.kotopogoda.uploader.core.data.upload

import com.kotopogoda.uploader.core.data.photo.PhotoDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Before
import org.junit.Test

class UploadQueueRepositoryTest {

    private val uploadItemDao = mockk<UploadItemDao>(relaxed = true)
    private val photoDao = mockk<PhotoDao>(relaxed = true)
    private lateinit var repository: UploadQueueRepository
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC)

    @Before
    fun setUp() {
        repository = UploadQueueRepository(uploadItemDao, photoDao, clock)
    }

    @Test
    fun `fetchQueued recovers stuck processing before fetching`() = runTest {
        coEvery { uploadItemDao.getByState(any(), any()) } returns emptyList()

        repository.fetchQueued(limit = 10)

        coVerifyOrder {
            uploadItemDao.requeueProcessingToQueued(
                processingState = UploadItemState.PROCESSING.rawValue,
                queuedState = UploadItemState.QUEUED.rawValue,
                updatedAt = any(),
            )
            uploadItemDao.getByState(UploadItemState.QUEUED.rawValue, 10)
        }

        val expectedNow = clock.instant().toEpochMilli()
        coVerify(exactly = 1) {
            uploadItemDao.requeueProcessingToQueued(
                processingState = UploadItemState.PROCESSING.rawValue,
                queuedState = UploadItemState.QUEUED.rawValue,
                updatedAt = expectedNow,
            )
        }
    }

    @Test
    fun `fetchQueued can skip recovery when requested`() = runTest {
        coEvery { uploadItemDao.getByState(any(), any()) } returns emptyList()

        repository.fetchQueued(limit = 3, recoverStuck = false)

        coVerify(exactly = 0) { uploadItemDao.requeueProcessingToQueued(any(), any(), any()) }
        coVerify { uploadItemDao.getByState(UploadItemState.QUEUED.rawValue, 3) }
    }

    @Test
    fun `recoverStuckProcessing returns affected rows`() = runTest {
        coEvery { uploadItemDao.requeueProcessingToQueued(any(), any(), any()) } returns 2

        val affected = repository.recoverStuckProcessing()

        assertEquals(2, affected)
        val expectedNow = clock.instant().toEpochMilli()
        coVerify(exactly = 1) {
            uploadItemDao.requeueProcessingToQueued(
                processingState = UploadItemState.PROCESSING.rawValue,
                queuedState = UploadItemState.QUEUED.rawValue,
                updatedAt = expectedNow,
            )
        }
    }

    @Test
    fun `markProcessing only updates queued items`() = runTest {
        coEvery { uploadItemDao.updateStateIfCurrent(any(), any(), any(), any()) } returns 1

        val result = repository.markProcessing(5L)

        assertTrue(result)
        val expectedNow = clock.instant().toEpochMilli()
        coVerify {
            uploadItemDao.updateStateIfCurrent(
                id = 5L,
                expectedState = UploadItemState.QUEUED.rawValue,
                newState = UploadItemState.PROCESSING.rawValue,
                updatedAt = expectedNow,
            )
        }
    }

    @Test
    fun `markProcessing returns false when state changed`() = runTest {
        coEvery { uploadItemDao.updateStateIfCurrent(any(), any(), any(), any()) } returns 0

        val result = repository.markProcessing(6L)

        assertFalse(result)
    }
}
