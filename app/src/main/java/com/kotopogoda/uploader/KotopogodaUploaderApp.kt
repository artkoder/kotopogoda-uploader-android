package com.kotopogoda.uploader

import android.app.Application
import androidx.work.Configuration
import com.kotopogoda.uploader.core.logging.AppLogger
import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.kotopogoda.uploader.core.network.logging.HttpLoggingController
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.notifications.UploadNotif
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        UploadNotif.ensureChannel(this)
        scope.launch {
            settingsRepository.flow.collect { settings ->
                appLogger.setEnabled(settings.appLogging)
                httpLoggingController.setEnabled(settings.httpLogging)
                networkClientProvider.updateBaseUrl(settings.baseUrl)
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = workManagerConfigurationDelegate

    override fun onTerminate() {
        super.onTerminate()
        scope.cancel()
    }
}
