package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository as UploadItemsRepository
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlin.text.Charsets

@Singleton
class UploadEnqueuer @Inject constructor(
    private val workManager: WorkManager,
    private val summaryStarter: UploadSummaryStarter,
    private val uploadItemsRepository: UploadItemsRepository,
    private val constraintsProvider: UploadConstraintsProvider,
) {

    @Suppress("UNUSED_PARAMETER")
    suspend fun enqueue(uri: Uri, idempotencyKey: String, displayName: String) {
        uploadItemsRepository.enqueue(uri)
        summaryStarter.ensureRunning()
        ensureUploadRunning()
    }

    suspend fun cancel(uri: Uri) {
        val uniqueName = uniqueName(uri)
        workManager.cancelAllWorkByTag(UploadTags.uniqueTag(uniqueName))
        val cancelledWhileProcessing = uploadItemsRepository.markCancelled(uri)
        if (cancelledWhileProcessing) {
            cancelUploadProcessorWork()
        }
        ensureUploadRunning()
    }

    suspend fun cancelAllUploads() {
        workManager.cancelAllWorkByTag(UploadTags.TAG_UPLOAD)
        workManager.cancelAllWorkByTag(UploadTags.TAG_POLL)
        cancelUploadProcessorWork()
        uploadItemsRepository.cancelAll()
        ensureUploadRunning()
    }

    suspend fun retry(metadata: UploadWorkMetadata) {
        val uri = metadata.uri ?: return
        val uniqueName = uniqueName(uri)
        workManager.cancelAllWorkByTag(UploadTags.uniqueTag(uniqueName))
        uploadItemsRepository.enqueue(uri)
        summaryStarter.ensureRunning()
        ensureUploadRunning()
    }

    fun isEnqueued(uri: Uri): Flow<Boolean> =
        uploadItemsRepository.observeQueuedOrProcessing(uri)

    fun uniqueName(uri: Uri): String = "upload:${sha256(uri.toString())}"

    companion object {
        const val KEY_URI = "uri"
        const val KEY_ITEM_ID = "itemId"
        const val KEY_IDEMPOTENCY_KEY = "idempotencyKey"
        const val KEY_UPLOAD_ID = "uploadId"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_SENT = "bytesSent"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_ERROR_KIND = "errorKind"
        const val KEY_HTTP_CODE = "httpCode"
        const val KEY_DELETED = "deleted"
        const val KEY_COMPLETION_STATE = "completionState"
        const val STATE_UPLOADED_DELETED = "uploadedDeleted"
        const val STATE_UPLOADED_AWAITING_DELETE = "uploadedAwaitingDelete"
        const val STATE_UPLOAD_COMPLETED_UNKNOWN = "uploadCompletedUnknown"

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }

    fun ensureUploadRunning() {
        val request = OneTimeWorkRequestBuilder<UploadProcessorWorker>()
            .setConstraints(constraintsProvider.buildConstraints())
            .build()
        workManager.enqueueUniqueWork(
            UploadProcessorWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun cancelUploadProcessorWork() {
        workManager.cancelUniqueWork(UPLOAD_PROCESSOR_WORK_NAME)
    }
}
