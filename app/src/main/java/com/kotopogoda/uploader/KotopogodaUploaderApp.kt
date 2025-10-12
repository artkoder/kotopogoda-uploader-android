package com.kotopogoda.uploader

import android.app.Application
import androidx.work.Configuration
import com.kotopogoda.uploader.core.logging.AppLogger
import com.kotopogoda.uploader.core.network.connectivity.ConnectivityObserver
import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.kotopogoda.uploader.core.network.logging.HttpLoggingController
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.notifications.UploadNotif
import com.kotopogoda.uploader.upload.UploadSummaryService
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
    lateinit var connectivityObserver: ConnectivityObserver

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        UploadNotif.ensureChannel(this)
        connectivityObserver.start()
        scope.launch {
            settingsRepository.flow.collect { settings ->
                appLogger.setEnabled(settings.appLogging)
                httpLoggingController.setEnabled(settings.httpLogging)
                networkClientProvider.updateBaseUrl(settings.baseUrl)
                if (settings.persistentQueueNotification) {
                    UploadSummaryService.ensureRunningIfNeeded(this@KotopogodaUploaderApp)
                }
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = workManagerConfigurationDelegate

    override fun onTerminate() {
        super.onTerminate()
        connectivityObserver.stop()
        scope.cancel()
    }
}
