package com.kotopogoda.uploader.core.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class KotopogodaApi @Inject constructor() {

    private val uploadSequence = AtomicLong(0L)
    private val statusAttempts = ConcurrentHashMap<String, Int>()

    suspend fun uploadCatWeatherReport(report: String): UploadResponse {
        delay(300)
        val id = uploadSequence.incrementAndGet().toString()
        statusAttempts[id] = 0
        return UploadResponse(uploadId = id)
    }

    suspend fun getUploadStatus(uploadId: String): UploadStatusResponse {
        delay(500)
        val attempts = statusAttempts.computeIfPresent(uploadId) { _, value -> value + 1 }
            ?: return UploadStatusResponse(processed = true, failed = false)
        val processed = attempts >= 2
        if (processed) {
            statusAttempts.remove(uploadId)
        }
        return UploadStatusResponse(processed = processed, failed = false)
    }
}

data class UploadResponse(
    val uploadId: String
)

data class UploadStatusResponse(
    val processed: Boolean,
    val failed: Boolean
)
