package com.kotopogoda.uploader.core.data.upload

import android.content.ContentResolver
import android.net.Uri
import com.kotopogoda.uploader.core.data.photo.MediaStorePhotoMetadata
import com.kotopogoda.uploader.core.data.photo.MediaStorePhotoMetadataReader
import com.kotopogoda.uploader.core.data.photo.PhotoDao
import com.kotopogoda.uploader.core.data.photo.PhotoEntity
import io.mockk.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
    private val clock = Clock.fixed(Instant.ofEpochMilli(100_000_000L), ZoneOffset.UTC)

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
        val providedDigest = "provided-digest"

        repository.enqueue(uri, idempotencyKey = "key-1", contentSha256 = providedDigest)

        coVerify {
            photoDao.upsert(withArg { entity ->
                assertEquals(uri.toString(), entity.id)
                assertEquals("DCIM/Camera/IMG_0001.jpg", entity.relPath)
                assertEquals("image/png", entity.mime)
                assertEquals(1280L, entity.size)
                assertEquals(10_000L, entity.takenAt)
                assertEquals(providedDigest, entity.sha256)
            })
        }
        coVerify {
            uploadItemDao.insert(withArg { entity ->
                assertEquals(uri.toString(), entity.photoId)
                assertEquals(uri.toString(), entity.uri)
                assertEquals("IMG_0001.jpg", entity.displayName)
                assertEquals(1280L, entity.size)
                assertEquals("key-1", entity.idempotencyKey)
            })
        }
    }

    @Test
    fun `enqueue throws when placeholder photo uri is not accessible`() = runTest {
        val uri = Uri.parse("content://media/external/images/media/2")
        coEvery { photoDao.getByUri(uri.toString()) } returns null
        every { metadataReader.read(uri) } returns null
        every { contentResolver.openInputStream(uri) } returns null

        val error = assertFailsWith<IllegalStateException> {
            repository.enqueue(uri, idempotencyKey = "key-2", contentSha256 = null)
        }

        assertEquals("Unable to open input stream for uri: $uri", error.message)
        coVerify(exactly = 0) { photoDao.upsert(any()) }
    }

    @Test
    fun `enqueue updates existing item metadata and key`() = runTest {
        val uri = Uri.parse("content://media/external/images/media/3")
        val photo = PhotoEntity(
            id = "photo-3",
            uri = uri.toString(),
            relPath = "DCIM/Camera/IMG_0003.jpg",
            sha256 = "hash",
            takenAt = null,
            size = 256L,
        )
        val existing = UploadItemEntity(
            id = 11L,
            photoId = photo.id,
            idempotencyKey = "old-key",
            uri = photo.uri,
            displayName = "old",
            size = 128L,
            state = UploadItemState.PROCESSING.rawValue,
            createdAt = 1L,
            updatedAt = 2L,
        )
        coEvery { photoDao.getByUri(uri.toString()) } returns photo
        coEvery { uploadItemDao.getByPhotoId(photo.id) } returns existing

        coEvery { photoDao.upsert(photo.copy(sha256 = "new-digest")) } returns Unit

        repository.enqueue(uri, idempotencyKey = "new-key", contentSha256 = "new-digest")

        val expectedNow = clock.instant().toEpochMilli()
        coVerify { photoDao.upsert(photo.copy(sha256 = "new-digest")) }
        coVerify {
            uploadItemDao.updateStateWithMetadata(
                id = existing.id,
                state = UploadItemState.QUEUED.rawValue,
                uri = photo.uri,
                displayName = "IMG_0003.jpg",
                size = photo.size,
                idempotencyKey = "new-key",
                enhanced = false,
                enhanceStrength = null,
                enhanceDelegate = null,
                enhanceMetricsLMean = null,
                enhanceMetricsPDark = null,
                enhanceMetricsBSharpness = null,
                enhanceMetricsNNoise = null,
                locationHiddenBySystem = false,
                updatedAt = expectedNow,
            )
        }
    }

    @Test
    fun `enqueue keeps stored key when new one is blank`() = runTest {
        val uri = Uri.parse("content://media/external/images/media/30")
        val photo = PhotoEntity(
            id = "photo-30",
            uri = uri.toString(),
            relPath = "DCIM/Camera/IMG_0030.jpg",
            sha256 = "hash",
            takenAt = null,
            size = 512L,
        )
        val existing = UploadItemEntity(
            id = 21L,
            photoId = photo.id,
            idempotencyKey = "existing-key",
            uri = photo.uri,
            displayName = "old",
            size = 256L,
            state = UploadItemState.PROCESSING.rawValue,
            createdAt = 1L,
            updatedAt = 2L,
        )
        coEvery { photoDao.getByUri(uri.toString()) } returns photo
        coEvery { uploadItemDao.getByPhotoId(photo.id) } returns existing

        repository.enqueue(uri, idempotencyKey = "", contentSha256 = "hash")

        val expectedNow = clock.instant().toEpochMilli()
        coVerify {
            uploadItemDao.updateStateWithMetadata(
                id = existing.id,
                state = UploadItemState.QUEUED.rawValue,
                uri = photo.uri,
                displayName = "IMG_0030.jpg",
                size = photo.size,
                idempotencyKey = "existing-key",
                enhanced = false,
                enhanceStrength = null,
                enhanceDelegate = null,
                enhanceMetricsLMean = null,
                enhanceMetricsPDark = null,
                enhanceMetricsBSharpness = null,
                enhanceMetricsNNoise = null,
                locationHiddenBySystem = false,
                updatedAt = expectedNow,
            )
        }
    }

    @Test
    fun `enqueue stores enhancement metadata when provided`() = runTest {
        val originalUri = Uri.parse("content://media/external/images/media/40")
        val enhancedUri = Uri.parse("file:///data/user/0/app/cache/enhanced.jpg")
        val photo = PhotoEntity(
            id = "photo-40",
            uri = originalUri.toString(),
            relPath = "DCIM/Camera/IMG_0040.jpg",
            sha256 = "original-digest",
            takenAt = null,
            size = 512L,
        )
        coEvery { photoDao.getById(photo.id) } returns photo
        coEvery { photoDao.getByUri(enhancedUri.toString()) } returns null
        coEvery { uploadItemDao.getByPhotoId(photo.id) } returns null
        coEvery { uploadItemDao.insert(any()) } returns 41L

        repository.enqueue(
            uri = enhancedUri,
            idempotencyKey = "enhanced-key",
            contentSha256 = "enhanced-digest",
            options = UploadEnqueueOptions(
                photoId = photo.id,
                overrideDisplayName = "IMG_0040.jpg",
                overrideSize = 1024L,
                enhancement = UploadEnhancementInfo(
                    strength = 0.75f,
                    delegate = "primary",
                    metrics = UploadEnhancementMetrics(
                        lMean = 0.5f,
                        pDark = 0.1f,
                        bSharpness = 0.8f,
                        nNoise = 0.05f,
                    ),
                    fileSize = 1024L,
                ),
            ),
        )

        coVerify {
            uploadItemDao.insert(withArg { entity ->
                assertEquals(photo.id, entity.photoId)
                assertEquals(enhancedUri.toString(), entity.uri)
                assertEquals("IMG_0040.jpg", entity.displayName)
                assertEquals(1024L, entity.size)
                assertEquals("enhanced-key", entity.idempotencyKey)
                assertTrue(entity.enhanced)
                assertEquals(0.75f, entity.enhanceStrength)
                assertEquals("primary", entity.enhanceDelegate)
                assertEquals(0.5f, entity.enhanceMetricsLMean)
                assertEquals(0.1f, entity.enhanceMetricsPDark)
                assertEquals(0.8f, entity.enhanceMetricsBSharpness)
                assertEquals(0.05f, entity.enhanceMetricsNNoise)
            })
        }
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
                updatedBefore = any(),
            )
            uploadItemDao.getByState(UploadItemState.QUEUED.rawValue, 10)
        }

        val expectedNow = clock.instant().toEpochMilli()
        val expectedThreshold = expectedNow - UploadQueueRepository.STUCK_TIMEOUT_MS
        coVerify(exactly = 1) {
            uploadItemDao.requeueProcessingToQueued(
                processingState = UploadItemState.PROCESSING.rawValue,
                queuedState = UploadItemState.QUEUED.rawValue,
                updatedAt = expectedNow,
                updatedBefore = expectedThreshold,
            )
        }
    }

    @Test
    fun `fetchQueued can skip recovery when requested`() = runTest {
        coEvery { uploadItemDao.getByState(any(), any()) } returns emptyList()

        repository.fetchQueued(limit = 3, recoverStuck = false)

        coVerify(exactly = 0) { uploadItemDao.requeueProcessingToQueued(any(), any(), any(), any()) }
        coVerify { uploadItemDao.getByState(UploadItemState.QUEUED.rawValue, 3) }
    }

    @Test
    fun `fetchQueued returns stored idempotency key`() = runTest {
        val entity = UploadItemEntity(
            id = 1L,
            photoId = "photo-1",
            idempotencyKey = "stored-key",
            uri = "content://media/external/images/media/4",
            displayName = "IMG_0004.jpg",
            size = 512L,
            state = UploadItemState.QUEUED.rawValue,
            createdAt = 1L,
            updatedAt = null,
        )
        coEvery { uploadItemDao.getByState(any(), any()) } returns listOf(entity)

        val items = repository.fetchQueued(limit = 1, recoverStuck = false)

        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("stored-key", item.idempotencyKey)
    }

    @Test
    fun `observeQueue filters succeeded older than retention`() = runTest {
        val flow = MutableSharedFlow<List<UploadItemEntity>>(replay = 1)
        every { uploadItemDao.observeAll() } returns flow

        val cutoff = clock.instant().toEpochMilli() - UploadQueueRepository.SUCCEEDED_RETENTION_MS
        val recent = UploadItemEntity(
            id = 2L,
            photoId = "recent",
            uri = "file:///recent.jpg",
            displayName = "recent.jpg",
            size = 42L,
            state = UploadItemState.SUCCEEDED.rawValue,
            createdAt = cutoff + 10,
            updatedAt = cutoff + 10,
        )
        val old = UploadItemEntity(
            id = 3L,
            photoId = "old",
            uri = "file:///old.jpg",
            displayName = "old.jpg",
            size = 42L,
            state = UploadItemState.SUCCEEDED.rawValue,
            createdAt = cutoff - 20,
            updatedAt = cutoff - 20,
        )

        val emitted = async { repository.observeQueue().first() }
        flow.emit(listOf(recent, old))

        val entries = emitted.await()
        assertEquals(1, entries.size)
        assertEquals(recent.id, entries.single().entity.id)
    }

    @Test
    fun `getQueueStats uses succeeded retention cutoff`() = runTest {
        coEvery { uploadItemDao.countByState(any()) } returns 0
        coEvery {
            uploadItemDao.countByStateUpdatedAfter(
                state = UploadItemState.SUCCEEDED.rawValue,
                updatedAfter = any(),
            )
        } returns 2

        val stats = repository.getQueueStats()

        assertEquals(2, stats.succeeded)
        val expectedCutoff = clock.instant().toEpochMilli() - UploadQueueRepository.SUCCEEDED_RETENTION_MS
        coVerify {
            uploadItemDao.countByStateUpdatedAfter(
                state = UploadItemState.SUCCEEDED.rawValue,
                updatedAfter = expectedCutoff,
            )
        }
    }

    @Test
    fun `requeueAllProcessing immediately returns processing items to queue`() = runTest {
        coEvery { uploadItemDao.requeueAllProcessingToQueued(any(), any(), any()) } returns 3

        val requeued = repository.requeueAllProcessing()

        val expectedNow = clock.instant().toEpochMilli()
        assertEquals(3, requeued)
        coVerify {
            uploadItemDao.requeueAllProcessingToQueued(
                processingState = UploadItemState.PROCESSING.rawValue,
                queuedState = UploadItemState.QUEUED.rawValue,
                updatedAt = expectedNow,
            )
        }
    }

    @Test
    fun `updateProcessingHeartbeat refreshes updated timestamp`() = runTest {
        coEvery { uploadItemDao.touchProcessing(any(), any(), any()) } returns 1

        repository.updateProcessingHeartbeat(id = 42L)

        val expectedNow = clock.instant().toEpochMilli()
        coVerify(exactly = 1) {
            uploadItemDao.touchProcessing(
                id = 42L,
                processingState = UploadItemState.PROCESSING.rawValue,
                updatedAt = expectedNow,
            )
        }
    }

    @Test
    fun `recoverStuckProcessing requeues stale processing items`() = runTest {
        coEvery { uploadItemDao.requeueProcessingToQueued(any(), any(), any(), any()) } returns 2

        val affected = repository.recoverStuckProcessing()

        assertEquals(2, affected)
        val expectedNow = clock.instant().toEpochMilli()
        val expectedThreshold = expectedNow - UploadQueueRepository.STUCK_TIMEOUT_MS
        coVerify(exactly = 1) {
            uploadItemDao.requeueProcessingToQueued(
                processingState = UploadItemState.PROCESSING.rawValue,
                queuedState = UploadItemState.QUEUED.rawValue,
                updatedAt = expectedNow,
                updatedBefore = expectedThreshold,
            )
        }
    }

    @Test
    fun `recoverStuckProcessing requeues only items older than provided threshold`() = runTest {
        val threshold = 123L
        coEvery { uploadItemDao.requeueProcessingToQueued(any(), any(), any(), any()) } returns 1

        val affected = repository.recoverStuckProcessing(updatedBefore = threshold)

        assertEquals(1, affected)
        val expectedNow = clock.instant().toEpochMilli()
        coVerify(exactly = 1) {
            uploadItemDao.requeueProcessingToQueued(
                processingState = UploadItemState.PROCESSING.rawValue,
                queuedState = UploadItemState.QUEUED.rawValue,
                updatedAt = expectedNow,
                updatedBefore = threshold,
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
