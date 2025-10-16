package com.kotopogoda.uploader.upload

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kotopogoda.uploader.MainActivity
import com.kotopogoda.uploader.R
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueEntry
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.notifications.UploadNotif
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class UploadSummaryService : LifecycleService() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var uploadQueueRepository: UploadQueueRepository

    @Inject
    lateinit var uploadEnqueuer: UploadEnqueuer

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
        val queueFlow = uploadQueueRepository.observeQueue()
            .map { entries -> entries.toSummary() }
        combine(settingsRepository.flow, queueFlow) { settings, summary ->
            settings to summary
        }.collect { (settings, summary) ->
            val shouldKeepForeground = summary.hasActive || settings.persistentQueueNotification
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
                summary.processing,
                summary.total,
                summary.queued
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
        lifecycleScope.launch {
            uploadEnqueuer.cancelAllUploads()
        }
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

    private fun List<UploadQueueEntry>.toSummary(): QueueSummary {
        if (isEmpty()) {
            return QueueSummary.Empty
        }
        var queued = 0
        var processing = 0
        var succeeded = 0
        var failed = 0
        for (entry in this) {
            when (entry.state) {
                UploadItemState.QUEUED -> queued += 1
                UploadItemState.PROCESSING -> processing += 1
                UploadItemState.SUCCEEDED -> succeeded += 1
                UploadItemState.FAILED -> failed += 1
            }
        }
        return QueueSummary(
            queued = queued,
            processing = processing,
            succeeded = succeeded,
            failed = failed,
        )
    }

    data class QueueSummary(
        val queued: Int,
        val processing: Int,
        val succeeded: Int,
        val failed: Int,
    ) {
        val total: Int get() = queued + processing + succeeded + failed
        val hasActive: Boolean get() = queued + processing > 0

        companion object {
            val Empty = QueueSummary(0, 0, 0, 0)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_CANCEL_ALL = "com.kotopogoda.uploader.upload.CANCEL_ALL"

        fun ensureRunningIfNeeded(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) {
                    return
                }
            }
            val intent = Intent(context, UploadSummaryService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
