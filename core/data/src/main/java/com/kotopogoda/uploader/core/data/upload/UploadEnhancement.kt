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
    val fallbackUsed: Boolean? = null,
    val fallbackCause: String? = null,
    val durationMsVulkan: Long? = null,
    val durationMsCpu: Long? = null,
    val delegateUsed: String? = null,
    val forceCpuReason: String? = null,
    val tileUsed: Boolean? = null,
    val tileSize: Int? = null,
    val tileOverlap: Int? = null,
    val tilesTotal: Int? = null,
    val tilesCompleted: Int? = null,
    val seamMaxDelta: Float? = null,
    val seamMeanDelta: Float? = null,
    val gpuAllocRetryCount: Int? = null,
)

data class UploadEnqueueOptions(
    val photoId: String? = null,
    val overrideDisplayName: String? = null,
    val enhancement: UploadEnhancementInfo? = null,
    val overrideSize: Long? = null,
)
