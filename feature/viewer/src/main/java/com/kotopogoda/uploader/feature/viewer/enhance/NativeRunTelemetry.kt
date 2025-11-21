package com.kotopogoda.uploader.feature.viewer.enhance

/**
 * Пакет телеметрии, который JNI возвращает после выполнения native-пайплайна.
 */
data class NativeRunTelemetry(
    val success: Boolean,
    val timingMs: Long,
    val usedVulkan: Boolean,
    val peakMemoryKb: Long,
    val cancelled: Boolean,
    val fallbackUsed: Boolean,
    val fallbackCauseCode: Int,
    val durationMsVulkan: Long,
    val durationMsCpu: Long,
    val tileUsed: Boolean,
    val tileSize: Int,
    val tileOverlap: Int,
    val tilesTotal: Int,
    val tilesCompleted: Int,
    val seamMaxDelta: Float,
    val seamMeanDelta: Float,
    val gpuAllocRetryCount: Int,
    val delegateUsed: String,
    val restPrecision: String,
)
