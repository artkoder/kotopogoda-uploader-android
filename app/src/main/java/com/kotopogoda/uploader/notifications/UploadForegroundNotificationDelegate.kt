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

        val progressText = when {
            kind == UploadForegroundKind.POLL ->
                context.getString(R.string.upload_notification_waiting_processing)
            progress in 0..100 ->
                context.getString(R.string.upload_notification_progress_percent, progress)
            else ->
                context.getString(R.string.upload_notification_in_progress)
        }

        val notification = NotificationCompat.Builder(context, UploadNotif.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_upload)
            .setContentTitle(displayName)
            .setContentText(progressText)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
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
