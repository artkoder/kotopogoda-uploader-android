package com.kotopogoda.uploader

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.logging.AppLogger
import com.kotopogoda.uploader.core.network.connectivity.NetworkMonitor
import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.kotopogoda.uploader.core.network.logging.HttpLoggingController
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.notifications.NotificationPermissionChecker
import com.kotopogoda.uploader.notifications.UploadNotif
import com.kotopogoda.uploader.upload.UploadSummaryService
import com.kotopogoda.uploader.upload.UploadStartupInitializer
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@HiltAndroidApp
class KotopogodaUploaderApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workManagerConfigurationDelegate: Configuration

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var httpLoggingController: HttpLoggingController

    @Inject
    lateinit var networkClientProvider: NetworkClientProvider

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var notificationPermissionChecker: NotificationPermissionChecker

    @Inject
    lateinit var uploadQueueRepository: UploadQueueRepository

    @Inject
    lateinit var uploadEnqueuer: UploadEnqueuer

    @Inject
    lateinit var uploadSummaryStarter: UploadSummaryStarter

    private val uploadStartupInitializer by lazy {
        UploadStartupInitializer(uploadQueueRepository, uploadEnqueuer, uploadSummaryStarter)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        WorkManager.initialize(this, workManagerConfigurationDelegate)
        UploadNotif.ensureChannel(this)
        networkMonitor.start()
        scope.launch(Dispatchers.IO) {
            uploadStartupInitializer.ensureUploadRunningIfQueued()
        }
        scope.launch {
            settingsRepository.flow.collect { settings ->
                appLogger.setEnabled(settings.appLogging)
                httpLoggingController.setEnabled(settings.httpLogging)
                networkClientProvider.updateBaseUrl(settings.baseUrl)
                if (settings.persistentQueueNotification && notificationPermissionChecker.canPostNotifications()) {
                    UploadSummaryService.ensureRunningIfNeeded(this@KotopogodaUploaderApp)
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = workManagerConfigurationDelegate

    override fun onTerminate() {
        super.onTerminate()
        networkMonitor.stop()
        scope.cancel()
    }
}
