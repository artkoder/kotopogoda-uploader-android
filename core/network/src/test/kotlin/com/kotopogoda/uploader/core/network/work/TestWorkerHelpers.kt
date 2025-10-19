package com.kotopogoda.uploader.core.network.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import com.kotopogoda.uploader.core.network.upload.UploadForegroundKind
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import java.util.UUID

internal data class ForegroundRequest(
    val displayName: String,
    val progress: Int,
    val workId: UUID,
    val kind: UploadForegroundKind,
)

internal class TestForegroundDelegate(private val context: Context) : UploadForegroundDelegate {
    val requests = mutableListOf<ForegroundRequest>()

    override fun create(displayName: String, progress: Int, workId: UUID, kind: UploadForegroundKind): ForegroundInfo {
        requests += ForegroundRequest(displayName, progress, workId, kind)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(displayName)
            .setProgress(100, progress.coerceIn(0, 100), progress < 0)
            .build()
        return ForegroundInfo(workId.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "test-uploads"
        private const val CHANNEL_NAME = "Test Uploads"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                manager?.createNotificationChannel(channel)
            }
        }
    }
}

internal object NoopUploadSummaryStarter : UploadSummaryStarter {
    override fun ensureRunning() = Unit
}
