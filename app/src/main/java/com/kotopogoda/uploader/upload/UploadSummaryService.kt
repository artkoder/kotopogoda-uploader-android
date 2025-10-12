package com.kotopogoda.uploader.upload

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.lifecycle.Observer
import androidx.work.getWorkInfosLiveData
import com.kotopogoda.uploader.MainActivity
import com.kotopogoda.uploader.R
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.notifications.UploadNotif
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class UploadSummaryService : LifecycleService() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        UploadNotif.ensureChannel(this)
        observerJob = lifecycleScope.launch { observeQueue() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_ALL -> cancelAllUploads()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        observerJob?.cancel()
        super.onDestroy()
    }

    private suspend fun observeQueue() {
        val workManager = WorkManager.getInstance(this)
        val query = WorkQuery.Builder
            .fromTags(listOf(UploadTags.TAG_UPLOAD, UploadTags.TAG_POLL))
            .build()
        val workFlow = workManager.workInfosFlow(query)
        combine(settingsRepository.flow, workFlow) { settings, infos ->
            settings to infos
        }.collect { (settings, infos) ->
            val summary = infos.toSummary()
            val hasActivePoll = infos.any { it.tags.contains(UploadTags.TAG_POLL) && it.state.isActive() }
            val shouldKeepForeground = summary.hasActive || hasActivePoll || settings.persistentQueueNotification
            if (!shouldKeepForeground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                val notification = buildNotification(summary)
                startForegroundInternal(notification)
            }
        }
    }

    private fun buildNotification(summary: QueueSummary): Notification {
        val contentIntent = queuePendingIntent()
        val cancelIntent = cancelAllPendingIntent()
        val builder = NotificationCompat.Builder(this, UploadNotif.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(getString(R.string.upload_summary_notification_title))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_cancel,
                getString(R.string.upload_summary_notification_cancel_all),
                cancelIntent
            )
        val text = if (summary.total == 0) {
            getString(R.string.upload_summary_notification_empty)
        } else {
            getString(
                R.string.upload_summary_notification_content,
                summary.running,
                summary.total,
                summary.enqueued
            )
        }
        return builder.setContentText(text).build()
    }

    private fun startForegroundInternal(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun cancelAllUploads() {
        val workManager = WorkManager.getInstance(this)
        workManager.cancelAllWorkByTag(UploadTags.TAG_UPLOAD)
        workManager.cancelAllWorkByTag(UploadTags.TAG_POLL)
    }

    private fun queuePendingIntent(): PendingIntent {
        val intent = MainActivity.createOpenQueueIntent(this)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelAllPendingIntent(): PendingIntent {
        val intent = Intent(this, UploadSummaryService::class.java).setAction(ACTION_CANCEL_ALL)
        return PendingIntent.getService(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun WorkInfo.State.isActive(): Boolean {
        return this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.RUNNING || this == WorkInfo.State.BLOCKED
    }

    private fun List<WorkInfo>.toSummary(): QueueSummary {
        val activeUploads = filter { it.tags.contains(UploadTags.TAG_UPLOAD) && it.state.isActive() }
        val running = activeUploads.count { it.state == WorkInfo.State.RUNNING }
        val enqueued = activeUploads.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        val total = activeUploads.size
        return QueueSummary(
            total = total,
            running = running,
            enqueued = enqueued,
        )
    }

    data class QueueSummary(
        val total: Int,
        val running: Int,
        val enqueued: Int,
    ) {
        val hasActive: Boolean get() = total > 0
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_CANCEL_ALL = "com.kotopogoda.uploader.upload.CANCEL_ALL"

        fun ensureRunningIfNeeded(context: Context) {
            val intent = Intent(context, UploadSummaryService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

private fun WorkManager.workInfosFlow(query: WorkQuery): Flow<List<WorkInfo>> = callbackFlow {
    val liveData = getWorkInfosLiveData(query)
    val observer = Observer<List<WorkInfo>> { infos ->
        trySend(infos).isSuccess
    }
    liveData.observeForever(observer)
    awaitClose { liveData.removeObserver(observer) }
}
