package com.kotopogoda.uploader.core.data.upload

data class UploadEnhancementMetrics(
    val lMean: Float,
    val pDark: Float,
    val bSharpness: Float,
    val nNoise: Float,
)

data class UploadEnhancementInfo(
    val strength: Float,
    val delegate: String,
    val metrics: UploadEnhancementMetrics,
    val fileSize: Long? = null,
    val previewTimingMs: Long? = null,
    val fullTimingMs: Long? = null,
    val usedVulkan: Boolean? = null,
    val peakMemoryMb: Float? = null,
    val cancelled: Boolean? = null,
)

data class UploadEnqueueOptions(
    val photoId: String? = null,
    val overrideDisplayName: String? = null,
    val enhancement: UploadEnhancementInfo? = null,
    val overrideSize: Long? = null,
)
