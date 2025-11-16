package com.kotopogoda.uploader.core.data.upload

import android.net.Uri

/**
 * Слушатель успешных переходов элементов очереди загрузки в состояние [UploadItemState.SUCCEEDED].
 */
fun interface UploadSuccessListener {
    suspend fun onUploadSucceeded(
        itemId: Long,
        photoId: String,
        contentUri: Uri?,
        displayName: String,
        sizeBytes: Long?,
        trigger: String,
        uploadId: String?,
    )

    companion object {
        const val TRIGGER_ACCEPTED: String = "accepted"
        const val TRIGGER_SUCCEEDED: String = "succeeded"
    }
}
