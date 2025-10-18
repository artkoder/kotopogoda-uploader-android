package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository as UploadItemsRepository
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlin.text.Charsets

@Singleton
class UploadEnqueuer @Inject constructor(
    private val workManager: WorkManager,
    private val summaryStarter: UploadSummaryStarter,
    private val uploadItemsRepository: UploadItemsRepository,
    private val constraintsProvider: UploadConstraintsProvider,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var constraintsObservationJob: Job? = null
    private var lastObservedWifiOnly: Boolean? = null

    suspend fun enqueue(uri: Uri, idempotencyKey: String, displayName: String) {
        uploadItemsRepository.enqueue(uri, idempotencyKey)
        summaryStarter.ensureRunning()
        scheduleDrain()
    }

    suspend fun cancel(uri: Uri) {
        val uniqueName = uniqueName(uri)
        workManager.cancelAllWorkByTag(UploadTags.uniqueTag(uniqueName))
        val cancelledWhileProcessing = uploadItemsRepository.markCancelled(uri)
        if (cancelledWhileProcessing) {
            cancelQueueDrainWork()
        }
        scheduleDrain()
    }

    suspend fun cancelAllUploads() {
        workManager.cancelAllWorkByTag(UploadTags.TAG_UPLOAD)
        workManager.cancelAllWorkByTag(UploadTags.TAG_POLL)
        cancelQueueDrainWork()
        uploadItemsRepository.cancelAll()
        scheduleDrain()
    }

    suspend fun retry(metadata: UploadWorkMetadata) {
        val uri = metadata.uri ?: return
        val uniqueName = uniqueName(uri)
        workManager.cancelAllWorkByTag(UploadTags.uniqueTag(uniqueName))
        val key = metadata.idempotencyKey ?: sha256(uri.toString())
        uploadItemsRepository.enqueue(uri, key)
        summaryStarter.ensureRunning()
        scheduleDrain()
    }

    fun isEnqueued(uri: Uri): Flow<Boolean> =
        uploadItemsRepository.observeQueuedOrProcessing(uri)

    fun uniqueName(uri: Uri): String = uniqueNameForUri(uri)

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

        fun uniqueNameForUri(uri: Uri): String = "upload:${sha256(uri.toString())}"

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }

    fun ensureUploadRunning() {
        scheduleDrain()
    }

    fun scheduleDrain() {
        ensureConstraintsObservation()
        val constraints = constraintsProvider.constraintsState.value ?: return
        enqueueDrainWork(constraints, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun cancelQueueDrainWork() {
        workManager.cancelUniqueWork(QUEUE_DRAIN_WORK_NAME)
    }

    private fun ensureConstraintsObservation() {
        if (constraintsObservationJob != null) {
            return
        }
        constraintsObservationJob = constraintsProvider.wifiOnlyUploadsState
            .filterNotNull()
            .onEach { wifiOnly ->
                val previousValue = lastObservedWifiOnly
                lastObservedWifiOnly = wifiOnly
                val switchedToMobile = previousValue == true && wifiOnly == false
                if (switchedToMobile) {
                    workManager.cancelAllWorkByTag(UploadTags.TAG_UPLOAD)
                    workManager.cancelAllWorkByTag(UploadTags.TAG_POLL)
                    uploadItemsRepository.requeueAllProcessing()
                }
                val policy = if (previousValue == null || previousValue != wifiOnly) {
                    ExistingWorkPolicy.REPLACE
                } else {
                    null
                }
                if (policy != null) {
                    val constraints = constraintsProvider.constraintsState.value ?: return@onEach
                    enqueueDrainWork(constraints, policy)
                }
                if (switchedToMobile) {
                    scheduleDrain()
                }
            }
            .launchIn(scope)
    }

    private fun enqueueDrainWork(constraints: Constraints, policy: ExistingWorkPolicy) {
        val builder = OneTimeWorkRequestBuilder<QueueDrainWorker>()
            .setConstraints(constraints)
        if (constraintsProvider.shouldUseExpeditedWork()) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        val request = builder.build()
        workManager.enqueueUniqueWork(
            QUEUE_DRAIN_WORK_NAME,
            policy,
            request,
        )
    }
}
