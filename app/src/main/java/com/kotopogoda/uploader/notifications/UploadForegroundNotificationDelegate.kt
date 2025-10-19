package com.kotopogoda.uploader.notifications

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.kotopogoda.uploader.R
import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import com.kotopogoda.uploader.core.network.upload.UploadForegroundKind
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID

private data class ForegroundContent(
    val title: String,
    val text: String,
    val progress: Int,
    val indeterminate: Boolean,
)

@Singleton
class UploadForegroundNotificationDelegate @Inject constructor(
    @ApplicationContext private val context: Context
) : UploadForegroundDelegate {

    override fun create(
        displayName: String,
        progress: Int,
        workId: UUID,
        kind: UploadForegroundKind
    ): ForegroundInfo {
        UploadNotif.ensureChannel(context)
        val notificationId = workId.hashCode()
        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(workId)

        val content = when (kind) {
            UploadForegroundKind.UPLOAD -> {
                val text = when {
                    progress in 0..100 ->
                        context.getString(R.string.upload_notification_progress_percent, progress)
                    else ->
                        context.getString(R.string.upload_notification_in_progress)
                }
                ForegroundContent(
                    title = displayName,
                    text = text,
                    progress = progress.coerceIn(0, 100),
                    indeterminate = progress < 0,
                )
            }
            UploadForegroundKind.POLL -> ForegroundContent(
                title = displayName,
                text = context.getString(R.string.upload_notification_waiting_processing),
                progress = 0,
                indeterminate = true,
            )
            UploadForegroundKind.DRAIN -> ForegroundContent(
                title = context.getString(R.string.upload_notification_drain_title),
                text = context.getString(R.string.upload_notification_drain_progress),
                progress = 0,
                indeterminate = true,
            )
        }

        val notification = NotificationCompat.Builder(context, UploadNotif.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, content.progress, content.indeterminate)
            .addAction(
                R.drawable.ic_cancel,
                context.getString(R.string.upload_notification_cancel),
                cancelIntent
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
}
