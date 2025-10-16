package com.kotopogoda.uploader.feature.queue

import android.net.Uri
import com.kotopogoda.uploader.core.network.upload.UploadWorkErrorKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkMetadata
import com.kotopogoda.uploader.core.network.uploadqueue.UploadQueueItem
import com.kotopogoda.uploader.core.network.uploadqueue.UploadQueueItemState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class QueueViewModelMappingTest {

    @Test
    fun progressBytesAreExposedInUiModel() {
        val id = UUID.randomUUID()
        val uri = Uri.parse("file:///tmp/photo.jpg")
        val queueItem = UploadQueueItem(
            id = id,
            metadata = UploadWorkMetadata(
                uniqueName = "unique",
                uri = uri,
                displayName = "photo.jpg",
                idempotencyKey = "key",
                kind = UploadWorkKind.UPLOAD
            ),
            kind = UploadWorkKind.UPLOAD,
            state = UploadQueueItemState.RUNNING,
            progress = 12,
            progressDisplayName = "photo.jpg",
            bytesSent = 512L * 1024,
            totalBytes = 4_200_000L,
            lastErrorKind = null,
            lastErrorHttpCode = null,
            deleted = null,
        )

        val uiModel = queueItem.toQueueItemUiModel()

        assertEquals(12, uiModel.progressPercent)
        assertEquals(512L * 1024, uiModel.bytesSent)
        assertEquals(4_200_000L, uiModel.totalBytes)
        assertNull(uiModel.lastErrorKind)
        assertEquals("photo.jpg", uiModel.title)
    }

    @Test
    fun failureMetadataFromOutputIsExposed() {
        val id = UUID.randomUUID()
        val uri = Uri.parse("file:///tmp/photo.jpg")
        val queueItem = UploadQueueItem(
            id = id,
            metadata = UploadWorkMetadata(
                uniqueName = "unique",
                uri = uri,
                displayName = "photo.jpg",
                idempotencyKey = "key",
                kind = UploadWorkKind.UPLOAD
            ),
            kind = UploadWorkKind.UPLOAD,
            state = UploadQueueItemState.FAILED,
            progress = null,
            progressDisplayName = null,
            bytesSent = null,
            totalBytes = null,
            lastErrorKind = UploadWorkErrorKind.HTTP,
            lastErrorHttpCode = 413,
            deleted = null,
        )

        val uiModel = queueItem.toQueueItemUiModel()

        assertEquals(UploadWorkErrorKind.HTTP, uiModel.lastErrorKind)
        assertEquals(413, uiModel.lastErrorHttpCode)
        assertTrue(uiModel.canRetry)
    }
}
