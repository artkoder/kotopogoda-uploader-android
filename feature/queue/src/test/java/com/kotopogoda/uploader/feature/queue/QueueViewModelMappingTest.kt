package com.kotopogoda.uploader.feature.queue

import com.kotopogoda.uploader.core.data.upload.UploadItemEntity
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueEntry
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.feature.queue.R
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
class QueueViewModelMappingTest {

    @Test
    fun processingItemIsMappedToIndeterminateProgress() {
        val entity = UploadItemEntity(
            id = 42,
            photoId = "photo-id",
            uri = "file:///tmp/photo.jpg",
            displayName = "photo.jpg",
            size = 4_200_000L,
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

        val uiModel = entry.toQueueItemUiModel(workInfo = null)

        assertNull(uiModel.progressPercent)
        assertEquals(42, uiModel.id)
        assertEquals("photo.jpg", uiModel.title)
        assertEquals(entity.size, uiModel.totalBytes)
        assertFalse(uiModel.canRetry)
        assertTrue(uiModel.waitingReasons.isEmpty())
        assertFalse(uiModel.isActiveTransfer)
        assertNull(uiModel.lastErrorMessage)
        assertNull(uiModel.completedAt)
    }

    @Test
    fun failureMetadataFromOutputIsExposed() {
        val entity = UploadItemEntity(
            id = 24,
            photoId = "photo-id",
            uri = "file:///tmp/photo.jpg",
            displayName = "photo.jpg",
            size = 1_024L,
            state = UploadItemState.FAILED.rawValue,
            createdAt = 1L,
            updatedAt = 2L,
            lastErrorKind = UploadErrorKind.HTTP.rawValue,
            httpCode = 413,
            lastErrorMessage = "Payload too large",
        )
        val entry = UploadQueueEntry(
            entity = entity,
            uri = null,
            state = UploadItemState.FAILED,
            lastErrorKind = UploadErrorKind.HTTP,
            lastErrorHttpCode = 413,
            lastErrorMessage = entity.lastErrorMessage,
        )

        val uiModel = entry.toQueueItemUiModel(workInfo = null)

        assertEquals(UploadErrorKind.HTTP, uiModel.lastErrorKind)
        assertEquals(413, uiModel.lastErrorHttpCode)
        assertEquals("Payload too large", uiModel.lastErrorMessage)
        assertEquals(0, uiModel.progressPercent)
        assertEquals(R.string.queue_status_failed, uiModel.statusResId)
        assertTrue(uiModel.waitingReasons.isEmpty())
        assertFalse(uiModel.isActiveTransfer)
        assertNull(uiModel.completedAt)
    }

    @Test
    fun authFailureHighlightsWarning() {
        val entity = UploadItemEntity(
            id = 77,
            photoId = "photo-id",
            uri = "file:///tmp/auth.jpg",
            displayName = "auth.jpg",
            size = 512L,
            state = UploadItemState.FAILED.rawValue,
            createdAt = 1L,
            updatedAt = 2L,
            lastErrorKind = UploadErrorKind.AUTH.rawValue,
            httpCode = 401,
        )
        val entry = UploadQueueEntry(
            entity = entity,
            uri = null,
            state = UploadItemState.FAILED,
            lastErrorKind = UploadErrorKind.AUTH,
            lastErrorHttpCode = 401,
            lastErrorMessage = null,
        )

        val uiModel = entry.toQueueItemUiModel(workInfo = null)

        assertTrue(uiModel.highlightWarning)
        assertTrue(uiModel.canRetry)
        assertNull(uiModel.completedAt)
    }

    @Test
    fun succeededItemExposesCompletionTimestamp() {
        val entity = UploadItemEntity(
            id = 101,
            photoId = "photo-id",
            uri = "file:///tmp/success.jpg",
            displayName = "success.jpg",
            size = 1_024L,
            state = UploadItemState.SUCCEEDED.rawValue,
            createdAt = 10L,
            updatedAt = 20L,
        )
        val entry = UploadQueueEntry(
            entity = entity,
            uri = null,
            state = UploadItemState.SUCCEEDED,
            lastErrorKind = null,
            lastErrorHttpCode = null,
        )

        val uiModel = entry.toQueueItemUiModel(workInfo = null)

        assertEquals(20L, uiModel.completedAt)
    }
}
