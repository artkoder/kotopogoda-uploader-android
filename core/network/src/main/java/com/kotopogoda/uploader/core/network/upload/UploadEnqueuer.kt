package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OverwritingInputMerger
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.workDataOf
import com.kotopogoda.uploader.core.network.work.PollStatusWorker
import com.kotopogoda.uploader.core.network.work.UploadWorker
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.text.Charsets

@Singleton
class UploadEnqueuer @Inject constructor(
    private val workManager: WorkManager,
    private val summaryStarter: UploadSummaryStarter,
) {

    fun enqueue(uri: Uri, idempotencyKey: String, displayName: String) {
        val uniqueName = uniqueName(uri)
        val upload = createUploadRequest(uniqueName, uri, idempotencyKey, displayName)
        val poll = createPollRequest(uniqueName, uri, idempotencyKey, displayName)

        enqueueChain(uniqueName, ExistingWorkPolicy.KEEP, upload, poll)
        summaryStarter.ensureRunning()
    }

    fun cancel(uri: Uri) {
        workManager.cancelUniqueWork(uniqueName(uri))
    }

    fun cancel(uniqueName: String) {
        workManager.cancelUniqueWork(uniqueName)
    }

    fun cancelAllUploads() {
        workManager.cancelAllWorkByTag(UploadTags.TAG_UPLOAD)
        workManager.cancelAllWorkByTag(UploadTags.TAG_POLL)
    }

    fun retry(metadata: UploadWorkMetadata) {
        val uniqueName = metadata.uniqueName ?: return
        val uri = metadata.uri ?: return
        val displayName = metadata.displayName ?: DEFAULT_FILE_NAME
        val idempotencyKey = metadata.idempotencyKey ?: return

        val upload = createUploadRequest(uniqueName, uri, idempotencyKey, displayName)
        val poll = createPollRequest(uniqueName, uri, idempotencyKey, displayName)

        enqueueChain(uniqueName, ExistingWorkPolicy.REPLACE, upload, poll)
        summaryStarter.ensureRunning()
    }

    fun getAllUploadsFlow(): Flow<List<WorkInfo>> {
        val query = WorkQuery.Builder
            .fromTags(listOf(UploadTags.TAG_UPLOAD, UploadTags.TAG_POLL))
            .build()
        return workManager.getWorkInfosFlow(query)
    }

    fun isEnqueued(uri: Uri): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName(uri))
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
        const val KEY_DELETED = "deleted"
        private const val INITIAL_BACKOFF_SECONDS = 10L
        private const val POLL_INITIAL_BACKOFF_SECONDS = 30L
        private const val DEFAULT_FILE_NAME = "photo.jpg"

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }

    private fun enqueueChain(
        uniqueName: String,
        policy: ExistingWorkPolicy,
        upload: OneTimeWorkRequest,
        poll: OneTimeWorkRequest
    ) {
        workManager.beginUniqueWork(uniqueName, policy, upload)
            .then(poll)
            .enqueue()
    }

    private fun createUploadRequest(
        uniqueName: String,
        uri: Uri,
        idempotencyKey: String,
        displayName: String
    ): OneTimeWorkRequest {
        val inputData = workDataOf(
            KEY_URI to uri.toString(),
            KEY_IDEMPOTENCY_KEY to idempotencyKey,
            KEY_DISPLAY_NAME to displayName
        )

        return OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputData)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(UploadTags.TAG_UPLOAD)
            .addTag(UploadTags.kindTag(UploadWorkKind.UPLOAD))
            .addTag(UploadTags.uniqueTag(uniqueName))
            .addTag(UploadTags.uriTag(uri.toString()))
            .addTag(UploadTags.displayNameTag(displayName))
            .addTag(UploadTags.keyTag(idempotencyKey))
            .build()
    }

    private fun createPollRequest(
        uniqueName: String,
        uri: Uri,
        idempotencyKey: String,
        displayName: String
    ): OneTimeWorkRequest {
        val inputData = workDataOf(
            KEY_URI to uri.toString(),
            KEY_DISPLAY_NAME to displayName
        )

        return OneTimeWorkRequestBuilder<PollStatusWorker>()
            .setInputData(inputData)
            .setInputMerger(OverwritingInputMerger::class.java)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, POLL_INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(UploadTags.TAG_POLL)
            .addTag(UploadTags.kindTag(UploadWorkKind.POLL))
            .addTag(UploadTags.uniqueTag(uniqueName))
            .addTag(UploadTags.uriTag(uri.toString()))
            .addTag(UploadTags.displayNameTag(displayName))
            .addTag(UploadTags.keyTag(idempotencyKey))
            .build()
    }

    private fun networkConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
