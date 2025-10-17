package com.kotopogoda.uploader.upload

import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import javax.inject.Inject

class UploadStartupInitializer @Inject constructor(
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadEnqueuer: UploadEnqueuer,
    private val uploadSummaryStarter: UploadSummaryStarter,
) {

    suspend fun ensureRunningIfNeeded() {
        if (uploadQueueRepository.hasQueued()) {
            uploadSummaryStarter.ensureRunning()
            uploadEnqueuer.ensureUploadRunning()
        }
    }
}
