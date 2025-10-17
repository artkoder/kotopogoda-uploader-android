package com.kotopogoda.uploader.upload

import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter

class UploadStartupInitializer(
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadEnqueuer: UploadEnqueuer,
    private val summaryStarter: UploadSummaryStarter,
) {

    suspend fun ensureUploadRunningIfQueued() {
        if (uploadQueueRepository.hasQueued()) {
            summaryStarter.ensureRunning()
            uploadEnqueuer.ensureUploadRunning()
        }
    }
}
