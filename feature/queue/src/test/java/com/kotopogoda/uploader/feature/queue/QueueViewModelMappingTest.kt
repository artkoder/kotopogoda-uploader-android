package com.kotopogoda.uploader.feature.queue

import android.net.Uri
import androidx.work.Data
import androidx.work.WorkInfo
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkErrorKind
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
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
        val progressData = Data.Builder()
            .putInt(UploadEnqueuer.KEY_PROGRESS, 12)
            .putLong(UploadEnqueuer.KEY_BYTES_SENT, 512L * 1024)
            .putLong(UploadEnqueuer.KEY_TOTAL_BYTES, 4_200_000L)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, "photo.jpg")
            .build()
        val workInfo = WorkInfo.Builder(id, WorkInfo.State.RUNNING)
            .setProgress(progressData)
            .setOutputData(Data.EMPTY)
            .addTag(UploadTags.TAG_UPLOAD)
            .addTag(UploadTags.kindTag(UploadWorkKind.UPLOAD))
            .addTag(UploadTags.uniqueTag("unique"))
            .addTag(UploadTags.uriTag(uri.toString()))
            .addTag(UploadTags.displayNameTag("photo.jpg"))
            .addTag(UploadTags.keyTag("key"))
            .build()

        val uiModel = workInfo.toQueueItemUiModel()

        assertEquals(12, uiModel.progress)
        assertEquals(512L * 1024, uiModel.bytesSent)
        assertEquals(4_200_000L, uiModel.totalBytes)
        assertNull(uiModel.errorKind)
        assertEquals("photo.jpg", uiModel.title)
    }

    @Test
    fun failureMetadataFromOutputIsExposed() {
        val id = UUID.randomUUID()
        val uri = Uri.parse("file:///tmp/photo.jpg")
        val outputData = Data.Builder()
            .putString(UploadEnqueuer.KEY_ERROR_KIND, UploadWorkErrorKind.HTTP.rawValue)
            .putInt(UploadEnqueuer.KEY_HTTP_CODE, 413)
            .build()
        val workInfo = WorkInfo.Builder(id, WorkInfo.State.FAILED)
            .setProgress(Data.EMPTY)
            .setOutputData(outputData)
            .addTag(UploadTags.TAG_UPLOAD)
            .addTag(UploadTags.kindTag(UploadWorkKind.UPLOAD))
            .addTag(UploadTags.uniqueTag("unique"))
            .addTag(UploadTags.uriTag(uri.toString()))
            .addTag(UploadTags.displayNameTag("photo.jpg"))
            .addTag(UploadTags.keyTag("key"))
            .build()

        val uiModel = workInfo.toQueueItemUiModel()

        assertEquals(UploadWorkErrorKind.HTTP, uiModel.errorKind)
        assertEquals(413, uiModel.errorHttpCode)
        assertTrue(uiModel.canRetry)
    }
}
