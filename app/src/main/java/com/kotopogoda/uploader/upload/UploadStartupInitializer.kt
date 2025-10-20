package com.kotopogoda.uploader.upload

import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import timber.log.Timber

class UploadStartupInitializer(
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadEnqueuer: UploadEnqueuer,
    private val summaryStarter: UploadSummaryStarter,
) {

    suspend fun ensureUploadRunningIfQueued() {
        if (uploadQueueRepository.hasQueued()) {
            Timber.tag("WorkManager").i(
                UploadLog.message(
                    category = "APP/STARTUP",
                    action = "summary_ensure_running_request",
                    details = arrayOf(
                        "source" to "startup_initializer",
                        "queued" to true,
                    ),
                ),
            )
            summaryStarter.ensureRunning()
            Timber.tag("WorkManager").i(
                UploadLog.message(
                    category = "APP/STARTUP",
                    action = "upload_ensure_running_request",
                    details = arrayOf(
                        "source" to "startup_initializer",
                        "queued" to true,
                    ),
                ),
            )
            uploadEnqueuer.ensureUploadRunning()
        }
    }
}
