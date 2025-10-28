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

    @Test
    fun queueItemErrorMessageShowsAuthFallback() {
        val entity = UploadItemEntity(
            id = 2L,
            photoId = "photo-2",
            uri = "content://photos/2",
            displayName = "photo2.jpg",
            size = 2_048L,
            state = UploadItemState.FAILED.rawValue,
            createdAt = 0L,
            updatedAt = 0L,
            lastErrorKind = UploadErrorKind.AUTH.rawValue,
            httpCode = 401,
            lastErrorMessage = null,
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

        every { resources.getString(R.string.queue_error_auth) } returns "Требуется повторная авторизация"
        every {
            resources.getString(R.string.queue_last_error, "Требуется повторная авторизация")
        } returns "Последняя ошибка: Требуется повторная авторизация"

        val message = queueItemErrorMessage(resources, uiModel)

        assertEquals("Последняя ошибка: Требуется повторная авторизация", message)
        verify { resources.getString(R.string.queue_error_auth) }
        verify { resources.getString(R.string.queue_last_error, "Требуется повторная авторизация") }
    }

    @Test
    fun queueItemStatusLabelFormatsCompletionTime() {
        every { resources.getString(R.string.queue_status_succeeded) } returns "Готово"
        every {
            resources.getString(R.string.queue_status_with_time, "Готово", "1 янв 12:00")
        } returns "Готово · 1 янв 12:00"

        val label = queueItemStatusLabel(
            resources = resources,
            statusResId = R.string.queue_status_succeeded,
            completedAt = 42L,
        ) { "1 янв 12:00" }

        assertEquals("Готово · 1 янв 12:00", label)
    }

    @Test
    fun queueItemStatusLabelSkipsTimeWhenMissing() {
        every { resources.getString(R.string.queue_status_failed) } returns "Ошибка"

        val label = queueItemStatusLabel(
            resources = resources,
            statusResId = R.string.queue_status_failed,
            completedAt = null,
        ) { throw IllegalStateException("Should not be called") }

        assertEquals("Ошибка", label)
    }
}
