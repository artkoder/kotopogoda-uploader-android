package com.kotopogoda.uploader.feature.queue

import android.content.res.Resources
import com.kotopogoda.uploader.core.data.upload.UploadItemEntity
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueEntry
import com.kotopogoda.uploader.core.work.UploadErrorKind
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import org.junit.Test

class QueueScreenTest {

    private val resources: Resources = mockk(relaxed = true)

    @Test
    fun queueItemErrorMessagePrefersServerMessage() {
        val entity = UploadItemEntity(
            id = 1L,
            photoId = "photo-1",
            uri = "content://photos/1",
            displayName = "photo.jpg",
            size = 1_024L,
            state = UploadItemState.FAILED.rawValue,
            createdAt = 0L,
            updatedAt = 0L,
            lastErrorKind = UploadErrorKind.HTTP.rawValue,
            httpCode = 422,
            lastErrorMessage = "X-Timestamp must be an integer",
        )
        val entry = UploadQueueEntry(
            entity = entity,
            uri = null,
            state = UploadItemState.FAILED,
            lastErrorKind = UploadErrorKind.HTTP,
            lastErrorHttpCode = 422,
            lastErrorMessage = entity.lastErrorMessage,
        )
        val uiModel = entry.toQueueItemUiModel(workInfo = null)

        every {
            resources.getString(R.string.queue_last_error, "X-Timestamp must be an integer")
        } returns "Последняя ошибка: X-Timestamp must be an integer"

        val message = queueItemErrorMessage(resources, uiModel)

        assertEquals("Последняя ошибка: X-Timestamp must be an integer", message)
        verify { resources.getString(R.string.queue_last_error, "X-Timestamp must be an integer") }
    }
}
