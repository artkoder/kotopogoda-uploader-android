package com.kotopogoda.uploader.core.data.upload

import android.net.Uri

/**
 * Слушатель успешных переходов элементов очереди загрузки в состояние [UploadItemState.SUCCEEDED].
 */
fun interface UploadSuccessListener {
    suspend fun onUploadSucceeded(event: UploadSuccessEvent)
}

/**
 * Описание успешного перехода элемента очереди в состояние «Готово».
 */
data class UploadSuccessEvent(
    val itemId: Long,
    val photoId: String,
    val contentUri: Uri?,
    val displayName: String,
    val sizeBytes: Long?,
    val trigger: UploadSuccessTrigger,
    val uploadId: String?,
)

/**
 * Источник события успеха.
 */
enum class UploadSuccessTrigger {
    ACCEPTED,
    SUCCEEDED,
}
