package com.kotopogoda.uploader.core.network.upload

import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.workDataOf
import com.kotopogoda.uploader.core.network.work.UploadWorker
import com.kotopogoda.uploader.core.network.upload.UploadWorkMetadata
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.text.Charsets

@Singleton
class UploadEnqueuer @Inject constructor(
    private val workManager: WorkManager
) {

    fun enqueue(uri: Uri, idempotencyKey: String, displayName: String) {
        val inputData = workDataOf(
            KEY_URI to uri.toString(),
            KEY_IDEMPOTENCY_KEY to idempotencyKey,
            KEY_DISPLAY_NAME to displayName
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(UploadTags.TAG_UPLOAD)
            .addTag(UploadTags.uniqueTag(uniqueName(uri)))
            .addTag(UploadTags.uriTag(uri.toString()))
            .addTag(UploadTags.displayNameTag(displayName))
            .addTag(UploadTags.keyTag(idempotencyKey))
            .build()

        workManager.enqueueUniqueWork(uniqueName(uri), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(uri: Uri) {
        workManager.cancelUniqueWork(uniqueName(uri))
    }

    fun cancel(uniqueName: String) {
        workManager.cancelUniqueWork(uniqueName)
    }

    fun retry(metadata: UploadWorkMetadata) {
        val uniqueName = metadata.uniqueName ?: return
        val uri = metadata.uri ?: return
        val displayName = metadata.displayName ?: DEFAULT_FILE_NAME
        val idempotencyKey = metadata.idempotencyKey ?: return

        val inputData = workDataOf(
            KEY_URI to uri.toString(),
            KEY_IDEMPOTENCY_KEY to idempotencyKey,
            KEY_DISPLAY_NAME to displayName
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(UploadTags.TAG_UPLOAD)
            .addTag(UploadTags.uniqueTag(uniqueName))
            .addTag(UploadTags.uriTag(uri.toString()))
            .addTag(UploadTags.displayNameTag(displayName))
            .addTag(UploadTags.keyTag(idempotencyKey))
            .build()

        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    fun getAllUploadsFlow(): Flow<List<WorkInfo>> {
        val query = WorkQuery.Builder
            .fromTags(listOf(UploadTags.TAG_UPLOAD))
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
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_PROGRESS = "progress"
        private const val INITIAL_BACKOFF_SECONDS = 10L
        private const val DEFAULT_FILE_NAME = "photo.jpg"

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
