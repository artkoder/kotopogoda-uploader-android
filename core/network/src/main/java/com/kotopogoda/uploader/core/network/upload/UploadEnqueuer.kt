package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.getWorkInfosByTagFlow
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository as UploadItemsRepository
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
        cancelUploadProcessorWork()
        uploadItemsRepository.markCancelled(uri)
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

    fun getAllUploadsFlow(): Flow<List<WorkInfo>> {
        val query = WorkQuery.Builder
            .fromTags(listOf(UploadTags.TAG_UPLOAD, UploadTags.TAG_POLL))
            .build()
        return workManager.getWorkInfosFlow(query)
    }

    fun isEnqueued(uri: Uri): Flow<Boolean> =
        workManager.getWorkInfosByTagFlow(UploadTags.uniqueTag(uniqueName(uri)))
            .map { infos ->
                infos.any { info ->
                    info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING
                }
            }

    fun uniqueName(uri: Uri): String = "upload:${sha256(uri.toString())}"

    companion object {
        const val KEY_URI = "uri"
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

    private fun ensureUploadRunning() {
        val workerClass = runCatching {
            Class.forName("com.kotopogoda.uploader.core.work.UploadProcessorWorker")
                .asSubclass(ListenableWorker::class.java)
        }.getOrNull() ?: return
        val constraints = constraintsProvider.buildConstraints()
        val request = OneTimeWorkRequest.Builder(workerClass)
            .setConstraints(constraints)
            .build()
        val workName = runCatching {
            workerClass.getField("WORK_NAME").get(null) as? String
        }.getOrNull() ?: UPLOAD_PROCESSOR_WORK_NAME
        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
    }

    private fun cancelUploadProcessorWork() {
        val workerClass = runCatching {
            Class.forName("com.kotopogoda.uploader.core.work.UploadProcessorWorker")
                .asSubclass(ListenableWorker::class.java)
        }.getOrNull() ?: return
        val workName = runCatching {
            workerClass.getField("WORK_NAME").get(null) as? String
        }.getOrNull() ?: UPLOAD_PROCESSOR_WORK_NAME
        workManager.cancelUniqueWork(workName)
    }
}
