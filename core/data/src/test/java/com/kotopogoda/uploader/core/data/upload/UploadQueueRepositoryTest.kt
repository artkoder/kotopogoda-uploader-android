package com.kotopogoda.uploader.core.data.upload

import android.content.ContentResolver
import android.net.Uri
import com.kotopogoda.uploader.core.data.photo.MediaStorePhotoMetadata
import com.kotopogoda.uploader.core.data.photo.MediaStorePhotoMetadataReader
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Before
import org.junit.Test

class UploadQueueRepositoryTest {

    private val uploadItemDao = mockk<UploadItemDao>(relaxed = true)
    private val photoDao = mockk<PhotoDao>(relaxed = true)
    private val metadataReader = mockk<MediaStorePhotoMetadataReader>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>()
    private lateinit var repository: UploadQueueRepository
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC)

    @Before
    fun setUp() {
        repository = UploadQueueRepository(uploadItemDao, photoDao, metadataReader, contentResolver, clock)
    }

    @Test
    fun `enqueue inserts placeholder when photo is missing`() = runTest {
        val uri = Uri.parse("content://media/external/images/media/1")
        coEvery { photoDao.getByUri(uri.toString()) } returns null
        every { metadataReader.read(uri) } returns MediaStorePhotoMetadata(
            displayName = "IMG_0001.jpg",
            size = 1280L,
            mimeType = "image/png",
            dateAddedMillis = 10_000L,
            dateModifiedMillis = null,
            dateTakenMillis = null,
            relativePath = "DCIM/Camera/",
        )
        coEvery { photoDao.upsert(any()) } returns Unit
        coEvery { uploadItemDao.getByPhotoId(uri.toString()) } returns null
        coEvery { uploadItemDao.insert(any()) } returns 1L
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(ByteArray(0))

        repository.enqueue(uri)

        coVerify {
            photoDao.upsert(withArg { entity ->
                assertEquals(uri.toString(), entity.id)
                assertEquals("DCIM/Camera/IMG_0001.jpg", entity.relPath)
                assertEquals("image/png", entity.mime)
                assertEquals(1280L, entity.size)
                assertEquals(10_000L, entity.takenAt)
            })
        }
        coVerify {
            uploadItemDao.insert(withArg { entity ->
                assertEquals(uri.toString(), entity.photoId)
                assertEquals(uri.toString(), entity.uri)
                assertEquals("IMG_0001.jpg", entity.displayName)
                assertEquals(1280L, entity.size)
            })
        }
        verify(exactly = 1) { contentResolver.openInputStream(uri) }
    }

    @Test
    fun `enqueue throws when placeholder photo uri is not accessible`() = runTest {
        val uri = Uri.parse("content://media/external/images/media/2")
        coEvery { photoDao.getByUri(uri.toString()) } returns null
        every { metadataReader.read(uri) } returns null
        every { contentResolver.openInputStream(uri) } returns null

        val error = assertFailsWith<IllegalStateException> {
            repository.enqueue(uri)
        }

        assertEquals("Unable to open input stream for uri: $uri", error.message)
        coVerify(exactly = 0) { photoDao.upsert(any()) }
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
