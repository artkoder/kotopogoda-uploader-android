package com.kotopogoda.uploader.feature.queue

import android.net.Uri
import com.kotopogoda.uploader.core.data.upload.UploadItemEntity
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueEntry
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.feature.queue.R
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
            uri = Uri.parse(entity.uri),
            state = UploadItemState.PROCESSING,
            lastErrorKind = null,
            lastErrorHttpCode = null,
        )

        val uiModel = entry.toQueueItemUiModel()

        assertNull(uiModel.progressPercent)
        assertEquals(42, uiModel.id)
        assertEquals("photo.jpg", uiModel.title)
        assertEquals(entity.size, uiModel.totalBytes)
        assertFalse(uiModel.canRetry)
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
        )
        val entry = UploadQueueEntry(
            entity = entity,
            uri = Uri.parse(entity.uri),
            state = UploadItemState.FAILED,
            lastErrorKind = UploadErrorKind.HTTP,
            lastErrorHttpCode = 413,
        )

        val uiModel = entry.toQueueItemUiModel()

        assertEquals(UploadErrorKind.HTTP, uiModel.lastErrorKind)
        assertEquals(413, uiModel.lastErrorHttpCode)
        assertEquals(0, uiModel.progressPercent)
        assertEquals(R.string.queue_status_failed, uiModel.statusResId)
    }
}
