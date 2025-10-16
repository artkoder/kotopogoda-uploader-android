package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadItemsRepository
import com.kotopogoda.uploader.core.network.work.UploadProcessorWorker
import com.kotopogoda.uploader.core.settings.WifiOnlyUploadsFlow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.text.Charsets

@Singleton
class UploadEnqueuer @Inject constructor(
    private val workManager: WorkManager,
    private val summaryStarter: UploadSummaryStarter,
    private val uploadItemsRepository: UploadItemsRepository,
    @WifiOnlyUploadsFlow wifiOnlyUploadsFlow: Flow<Boolean>,
) {

    private val wifiOnlyUploadsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val enqueueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifiOnlyUploadsState = wifiOnlyUploadsFlow.stateIn(
        scope = wifiOnlyUploadsScope,
        started = SharingStarted.Eagerly,
        initialValue = false,
    )

    fun enqueue(uri: Uri, idempotencyKey: String, displayName: String) {
        val uniqueName = uniqueName(uri)
        enqueueScope.launch {
            uploadItemsRepository.upsertPending(uniqueName, uri, idempotencyKey, displayName)
            ensureUploadRunning()
        }
        summaryStarter.ensureRunning()
    }

    fun cancel(uri: Uri) {
        cancel(uniqueName(uri))
    }

    fun cancel(uniqueName: String) {
        enqueueScope.launch {
            uploadItemsRepository.markCancelled(uniqueName)
            ensureUploadRunning()
        }
    }

    fun cancelAllUploads() {
        enqueueScope.launch {
            uploadItemsRepository.markAllCancelled()
            ensureUploadRunning()
        }
    }

    fun retry(metadata: UploadWorkMetadata) {
        val uniqueName = metadata.uniqueName ?: return
        enqueueScope.launch {
            uploadItemsRepository.markPending(uniqueName)
            ensureUploadRunning()
        }
    }

    fun getAllUploadsFlow(): Flow<List<UploadQueueSnapshot>> {
        return uploadItemsRepository.observeAll()
            .map { entities ->
                entities.map { entity ->
                    UploadQueueSnapshot(
                        uniqueName = entity.uniqueName,
                        uri = entity.uri,
                        idempotencyKey = entity.idempotencyKey,
                        displayName = entity.displayName,
                        state = UploadItemState.fromRawValue(entity.state),
                        lastErrorKind = entity.errorKind,
                        lastErrorHttpCode = entity.errorHttpCode,
                    )
                }
            }
    }

    fun isEnqueued(uri: Uri): Flow<Boolean> =
        uploadItemsRepository.observeAll()
            .map { items ->
                val uniqueName = uniqueName(uri)
                items.any { item ->
                    item.uniqueName == uniqueName &&
                        UploadItemState.fromRawValue(item.state) != UploadItemState.CANCELLED
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
        private const val UPLOAD_QUEUE_NAME = "upload-queue"

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }

    private suspend fun ensureUploadRunning() {
        val request = OneTimeWorkRequestBuilder<UploadProcessorWorker>()
            .setConstraints(networkConstraints())
            .addTag(UploadTags.TAG_UPLOAD)
            .build()

        workManager.enqueueUniqueWork(
            UPLOAD_QUEUE_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    internal fun networkConstraints(): Constraints {
        val requiredNetworkType = if (wifiOnlyUploadsState.value) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(requiredNetworkType)
            .build()
    }
}

data class UploadQueueSnapshot(
    val uniqueName: String,
    val uri: String,
    val idempotencyKey: String,
    val displayName: String,
    val state: UploadItemState,
    val lastErrorKind: String?,
    val lastErrorHttpCode: Int?,
)
