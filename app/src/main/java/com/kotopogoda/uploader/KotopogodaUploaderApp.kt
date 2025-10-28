package com.kotopogoda.uploader

import android.app.Application
import android.os.Looper
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.logging.AppLogger
import com.kotopogoda.uploader.core.logging.diagnostic.DiagnosticContextProvider
import com.kotopogoda.uploader.core.network.connectivity.NetworkMonitor
import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.kotopogoda.uploader.core.network.logging.HttpLoggingController
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.di.LoggingWorkerFactory
import com.kotopogoda.uploader.ml.ModelChecksumVerifier
import com.kotopogoda.uploader.notifications.NotificationPermissionChecker
import com.kotopogoda.uploader.notifications.UploadNotif
import com.kotopogoda.uploader.upload.UploadStartupInitializer
import com.kotopogoda.uploader.work.UploadWorkObserver
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import timber.log.Timber

@HiltAndroidApp
class KotopogodaUploaderApp : Application(), Configuration.Provider {

    @Inject
    lateinit var loggingWorkerFactory: LoggingWorkerFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var appLogger: AppLogger

    @Inject
    lateinit var diagnosticContextProvider: DiagnosticContextProvider

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

    @Inject
    lateinit var uploadWorkObserver: UploadWorkObserver

    private val uploadStartupInitializer by lazy {
        UploadStartupInitializer(uploadQueueRepository, uploadEnqueuer, uploadSummaryStarter)
    }

    private val crashExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) {
            return@CoroutineExceptionHandler
        }
        val thread = Thread.currentThread()
        logFatalCrash(
            origin = "coroutine",
            thread = thread,
            throwable = throwable,
        )
        previousExceptionHandler?.uncaughtException(thread, throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + crashExceptionHandler)

    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null

    private val workManagerConfigurationDelegate: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(loggingWorkerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .setExecutor(Executors.newFixedThreadPool(2))
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        appLogger.setEnabled(true)
        scope.launch(Dispatchers.IO) {
            ModelChecksumVerifier.verify(this@KotopogodaUploaderApp)
        }
        installCrashHandlers()

        Timber.tag("WorkManager").i(
            UploadLog.message(
                category = "WORK/Factory",
                action = "initializer_disabled",
            ),
        )
        if (workManagerInitializationGuard.compareAndSet(false, true)) {
            WorkManager.initialize(this, workManagerConfiguration)
            Timber.tag("WorkManager").i(
                UploadLog.message(
                    category = "WORK/Factory",
                    action = "app_init",
                ),
            )
        } else {
            Timber.tag("WorkManager").w(
                UploadLog.message(
                    category = "WORK/Factory",
                    action = "app_init_duplicate",
                ),
            )
        }
        UploadNotif.ensureChannel(this)
        networkMonitor.start()
        httpLoggingController.setEnabled(true)
        UploadLog.setDiagnosticContextProvider(diagnosticContextProvider)
        uploadWorkObserver.start(scope)
        Timber.tag("app").i(
            UploadLog.message(
                category = "APP/START",
                action = "application_create",
            )
        )
        logNotificationPermissionState()
        scope.launch(Dispatchers.IO) {
            uploadStartupInitializer.ensureUploadRunningIfQueued()
        }
        scope.launch {
            settingsRepository.flow.collect { settings ->
                diagnosticContextProvider.updateSettings(settings)
                appLogger.setEnabled(settings.appLogging)
                httpLoggingController.setEnabled(settings.httpLogging)
                networkClientProvider.updateBaseUrl(settings.baseUrl)
                Timber.tag("app").i(
                    UploadLog.message(
                        category = "CFG/STATE",
                        action = "settings", 
                        details = arrayOf(
                            "base_url" to settings.baseUrl,
                            "app_logging" to settings.appLogging,
                            "http_logging" to settings.httpLogging,
                            "persistent_queue_notification" to settings.persistentQueueNotification,
                        ),
                    )
                )
            }
        }
    }

    private fun logNotificationPermissionState() {
        scope.launch {
            notificationPermissionChecker.permissionFlow()
                .distinctUntilChanged()
                .collect { granted ->
                    UploadLog.updateDiagnosticExtras(mapOf("notification_permission" to granted.toString()))
                    Timber.tag("app").i(
                        UploadLog.message(
                            category = "PERM/STATE",
                            action = "notifications",
                            details = arrayOf(
                                "granted" to granted,
                            ),
                        )
                    )
                }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = workManagerConfigurationDelegate

    override fun onTerminate() {
        super.onTerminate()
        networkMonitor.stop()
        scope.cancel()
        previousExceptionHandler?.let(Thread::setDefaultUncaughtExceptionHandler)
    }

    private companion object {
        private val workManagerInitializationGuard = AtomicBoolean(false)
    }

    private fun installCrashHandlers() {
        val mainThread = Looper.getMainLooper()?.thread
        val existing = Thread.getDefaultUncaughtExceptionHandler()
        previousExceptionHandler = existing
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logFatalCrash(
                origin = "thread",
                thread = thread,
                throwable = throwable,
            )
            if (existing != null) {
                runCatching { existing.uncaughtException(thread, throwable) }
            } else if (thread == mainThread) {
                runCatching {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
    }

    private fun logFatalCrash(origin: String, thread: Thread, throwable: Throwable) {
        try {
            Timber.tag("app").wtf(
                throwable,
                UploadLog.message(
                    category = "FATAL/CRASH",
                    action = "uncaught_exception",
                    details = arrayOf(
                        "origin" to origin,
                        "thread" to thread.name,
                        "thread_id" to thread.id,
                    ),
                ),
            )
        } catch (loggingError: Throwable) {
            Log.e(
                "KotopogodaApp",
                "Failed to log fatal crash",
                loggingError,
            )
        }
    }
}
