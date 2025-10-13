package com.kotopogoda.uploader.upload

import android.content.Context
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.notifications.NotificationPermissionChecker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadSummaryStarterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationPermissionChecker: NotificationPermissionChecker,
) : UploadSummaryStarter {
    override fun ensureRunning() {
        if (!notificationPermissionChecker.canPostNotifications()) {
            return
        }
        UploadSummaryService.ensureRunningIfNeeded(context)
    }
}
