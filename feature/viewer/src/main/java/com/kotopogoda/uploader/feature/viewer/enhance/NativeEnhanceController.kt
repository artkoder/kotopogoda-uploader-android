package com.kotopogoda.uploader.feature.viewer.enhance

import android.content.res.AssetManager
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Контроллер для управления нативным NCNN-движком улучшения фотографий.
 * Связывает JNI API и управляет жизненным циклом нативных сетей.
 */
class NativeEnhanceController(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private var nativeHandle: Long = 0L
    private val initializationFlag = AtomicInteger(UNINITIALIZED)
    private val activeOperations = AtomicInteger(0)
    private var lastForceCpuReason: String? = null
    private var lastForceCpuFlag: Boolean = false
    private var lastDelegatePlan: String = DELEGATE_PLAN_CPU
    private var lastDelegateAvailable: String = DELEGATE_CPU_ONLY
    private var lastDelegateUsed: String = DELEGATE_CPU
    private var lastVulkanAvailable: Boolean = false

    enum class PreviewProfile {
        BALANCED,
        QUALITY,
    }

    enum class State {
        IDLE,
        COMPUTING_PREVIEW,
        READY,
        COMPUTING_FULL,
        CANCELLED,
        ERROR,
    }

    enum class FallbackCause(val code: Int) {
        NONE(0),
        LOAD_FAILED(1),
        EXTRACT_FAILED(2);

        companion object {
            fun fromCode(code: Long): FallbackCause? {
                val matched = values().firstOrNull { it.code.toLong() == code }
                return matched?.takeUnless { it == NONE }
            }
        }
    }

    data class ModelChecksums(
        val param: String,
        val bin: String,
    )

    data class ModelFiles(
        val paramFile: String,
        val binFile: String,
    )

    data class InitParams(
        val assetManager: AssetManager,
        val modelsDir: File,
        val zeroDceChecksums: ModelChecksums,
        val restormerChecksums: ModelChecksums,
        val zeroDceFiles: ModelFiles,
        val restormerFiles: ModelFiles,
        val previewProfile: PreviewProfile,
        val forceCpu: Boolean = true,
        val forceCpuReason: String = DeviceGpuPolicy.forceCpuReason,
    )

    data class IntegrityFailure(
        val filePath: String,
        val expectedChecksum: String,
        val actualChecksum: String,
    )

    class ModelIntegrityException(
        val failure: IntegrityFailure,
    ) : IllegalStateException(
        "Повреждение модели ${failure.filePath}: ожидалось ${failure.expectedChecksum}, получено ${failure.actualChecksum}"
    )

    data class PreviewResult(
        val success: Boolean,
        val timingMs: Long,
        val usedVulkan: Boolean,
        val peakMemoryMb: Float,
        val fallbackUsed: Boolean,
        val fallbackCause: FallbackCause?,
        val durationMsVulkan: Long?,
        val durationMsCpu: Long?,
        val delegateUsed: String,
        val forceCpuReason: String?,
        val tileUsed: Boolean,
        val tileSize: Int,
        val tileOverlap: Int,
        val tilesTotal: Int,
        val tilesCompleted: Int,
        val seamMaxDelta: Float,
        val seamMeanDelta: Float,
        val gpuAllocRetryCount: Int,
    )

    data class FullResult(
        val bitmap: Bitmap,
        val timingMs: Long,
        val usedVulkan: Boolean,
        val peakMemoryMb: Float,
        val cancelled: Boolean,
        val fallbackUsed: Boolean,
        val fallbackCause: FallbackCause?,
        val durationMsVulkan: Long?,
        val durationMsCpu: Long?,
        val delegateUsed: String,
        val forceCpuReason: String?,
        val tileUsed: Boolean,
        val tileSize: Int,
        val tileOverlap: Int,
        val tilesTotal: Int,
        val tilesCompleted: Int,
        val seamMaxDelta: Float,
        val seamMeanDelta: Float,
        val gpuAllocRetryCount: Int,
    )

    data class ProgressInfo(
        val progress: Float,
        val currentStage: String,
    )

    suspend fun initialize(params: InitParams) = withContext(dispatcher) {
        if (!initializationFlag.compareAndSet(UNINITIALIZED, INITIALIZING)) {
            Timber.tag(LOG_TAG).w("Инициализация уже выполнена или в процессе")
            return@withContext
        }

        try {
            val handle = nativeInit(
                params.assetManager,
                params.modelsDir.absolutePath,
                params.zeroDceChecksums.param,
                params.zeroDceChecksums.bin,
                params.restormerChecksums.param,
                params.restormerChecksums.bin,
                params.previewProfile.ordinal,
                params.forceCpu,
            )

            if (handle == 0L) {
                initializationFlag.set(INITIALIZATION_FAILED)
                val integrityFailure = consumeIntegrityFailure()
                if (integrityFailure != null) {
                    logIntegrityFailure(integrityFailure, params)
                    throw ModelIntegrityException(integrityFailure)
                }
                throw IllegalStateException("Нативная инициализация вернула нулевой handle")
            }

            nativeHandle = handle
            initializationFlag.set(INITIALIZED)
            lastForceCpuReason = params.forceCpuReason
            lastForceCpuFlag = params.forceCpu

            Timber.tag(LOG_TAG).i(
                "Нативный контроллер инициализирован: handle=%d profile=%s",
                handle,
                params.previewProfile,
            )

            val gpuDelegateAvailable = nativeIsGpuDelegateAvailable(handle)
            val delegatePlan = if (params.forceCpu) DELEGATE_PLAN_CPU else DELEGATE_PLAN_GPU
            val delegateAvailable = if (params.forceCpu) {
                DELEGATE_CPU_ONLY
            } else if (gpuDelegateAvailable) {
                DELEGATE_GPU
            } else {
                DELEGATE_CPU
            }
            val delegateUsed = if (params.forceCpu) {
                DELEGATE_CPU
            } else if (gpuDelegateAvailable) {
                DELEGATE_GPU
            } else {
                DELEGATE_CPU
            }

            lastDelegatePlan = delegatePlan
            lastDelegateAvailable = delegateAvailable
            lastDelegateUsed = delegateUsed
            lastVulkanAvailable = gpuDelegateAvailable

            val delegateMetadata = delegateSnapshotPayload()
            val modelPayload = mapOf(
                "zero_dce_param_file" to params.zeroDceFiles.paramFile,
                "zero_dce_bin_file" to params.zeroDceFiles.binFile,
                "restormer_param_file" to params.restormerFiles.paramFile,
                "restormer_bin_file" to params.restormerFiles.binFile,
            )
            val commonDelegatePayload = delegateMetadata + mapOf(
                "handle" to handle,
            )

            EnhanceLogging.logEvent(
                "native_controller_init",
                mapOf(
                    "models_dir" to params.modelsDir.absolutePath,
                    "preview_profile" to params.previewProfile.name,
                    "zero_dce_param_checksum" to params.zeroDceChecksums.param.take(8),
                    "zero_dce_bin_checksum" to params.zeroDceChecksums.bin.take(8),
                    "restormer_param_checksum" to params.restormerChecksums.param.take(8),
                    "restormer_bin_checksum" to params.restormerChecksums.bin.take(8),
                ) + modelPayload + commonDelegatePayload,
            )

            EnhanceLogging.logEvent(
                "native_delegate_status",
                commonDelegatePayload,
            )
        } catch (error: Exception) {
            initializationFlag.set(INITIALIZATION_FAILED)
            Timber.tag(LOG_TAG).e(error, "Ошибка инициализации нативного контроллера")
            throw error
        }
    }

    suspend fun runPreview(
        sourceBitmap: Bitmap,
        strength: Float,
        onProgress: (ProgressInfo) -> Unit = {},
    ): PreviewResult = withContext(dispatcher) {
        checkInitialized()
        activeOperations.incrementAndGet()

        try {
            val previewStartMetadata = delegateSnapshotPayload()
            EnhanceLogging.logEvent(
                "native_preview_start",
                mapOf(
                    "strength" to strength,
                    "width" to sourceBitmap.width,
                    "height" to sourceBitmap.height,
                    "tile_size" to NATIVE_TILE_SIZE,
                    "tile_overlap" to NATIVE_TILE_OVERLAP,
                ) + previewStartMetadata,
            )

            val startTime = System.currentTimeMillis()
            val telemetry = nativeRunPreview(nativeHandle, sourceBitmap, strength)
            val elapsed = System.currentTimeMillis() - startTime

            val success = telemetry.success
            val timing = telemetry.timingMs
            val usedVulkan = telemetry.usedVulkan
            val peakMemory = telemetry.peakMemoryKb.toFloat() / 1024f
            val fallbackUsed = telemetry.fallbackUsed
            val fallbackCause = FallbackCause.fromCode(telemetry.fallbackCauseCode.toLong())
            val durationVulkan = telemetry.durationMsVulkan.takeIf { it > 0 }
            val durationCpu = telemetry.durationMsCpu.takeIf { it > 0 }

            lastDelegateUsed = telemetry.delegateUsed
            val previewCompleteMetadata = delegateSnapshotPayload()
            EnhanceLogging.logEvent(
                "native_preview_complete",
                mapOf(
                    "success" to success,
                    "timing_ms" to timing,
                    "elapsed_ms" to elapsed,
                    "used_vulkan" to usedVulkan,
                    "peak_memory_mb" to peakMemory,
                    "cancelled" to telemetry.cancelled,
                    "fallback_used" to fallbackUsed,
                    "fallback_cause" to fallbackCause?.name?.lowercase(),
                    "duration_ms_vulkan" to durationVulkan,
                    "duration_ms_cpu" to durationCpu,
                    "tile_used" to telemetry.tileUsed,
                    "tile_size" to telemetry.tileSize,
                    "tile_overlap" to telemetry.tileOverlap,
                    "tiles_total" to telemetry.tilesTotal,
                    "tiles_completed" to telemetry.tilesCompleted,
                    "seam_max_delta" to telemetry.seamMaxDelta,
                    "seam_mean_delta" to telemetry.seamMeanDelta,
                    "gpu_alloc_retry_count" to telemetry.gpuAllocRetryCount,
                ) + previewCompleteMetadata,
            )

            PreviewResult(
                success = success,
                timingMs = timing,
                usedVulkan = usedVulkan,
                peakMemoryMb = peakMemory,
                fallbackUsed = fallbackUsed,
                fallbackCause = fallbackCause,
                durationMsVulkan = durationVulkan,
                durationMsCpu = durationCpu,
                delegateUsed = telemetry.delegateUsed,
                forceCpuReason = lastForceCpuReason,
                tileUsed = telemetry.tileUsed,
                tileSize = telemetry.tileSize,
                tileOverlap = telemetry.tileOverlap,
                tilesTotal = telemetry.tilesTotal,
                tilesCompleted = telemetry.tilesCompleted,
                seamMaxDelta = telemetry.seamMaxDelta,
                seamMeanDelta = telemetry.seamMeanDelta,
                gpuAllocRetryCount = telemetry.gpuAllocRetryCount,
            )
        } finally {
            activeOperations.decrementAndGet()
        }
    }

    suspend fun runFull(
        sourceBitmap: Bitmap,
        strength: Float,
        outputFile: File,
        quality: Int = 95,
        onProgress: (ProgressInfo) -> Unit = {},
    ): FullResult = withContext(dispatcher) {
        checkInitialized()
        activeOperations.incrementAndGet()

        try {
            val fullStartMetadata = delegateSnapshotPayload()
            EnhanceLogging.logEvent(
                "native_full_start",
                mapOf(
                    "strength" to strength,
                    "width" to sourceBitmap.width,
                    "height" to sourceBitmap.height,
                    "output_file" to outputFile.absolutePath,
                    "quality" to quality,
                    "tile_size" to NATIVE_TILE_SIZE,
                    "tile_overlap" to NATIVE_TILE_OVERLAP,
                ) + fullStartMetadata,
            )

            val startTime = System.currentTimeMillis()
            val resultBitmap = Bitmap.createBitmap(
                sourceBitmap.width,
                sourceBitmap.height,
                Bitmap.Config.ARGB_8888,
            )

            val telemetry = nativeRunFull(nativeHandle, sourceBitmap, strength, resultBitmap)
            val elapsed = System.currentTimeMillis() - startTime

            val success = telemetry.success
            val timing = telemetry.timingMs
            val usedVulkan = telemetry.usedVulkan
            val peakMemory = telemetry.peakMemoryKb.toFloat() / 1024f
            val cancelled = telemetry.cancelled
            val fallbackUsed = telemetry.fallbackUsed
            val fallbackCause = FallbackCause.fromCode(telemetry.fallbackCauseCode.toLong())
            val durationVulkan = telemetry.durationMsVulkan.takeIf { it > 0 }
            val durationCpu = telemetry.durationMsCpu.takeIf { it > 0 }

            lastDelegateUsed = telemetry.delegateUsed
            val fullCompleteMetadata = delegateSnapshotPayload()
            EnhanceLogging.logEvent(
                "native_full_complete",
                mapOf(
                    "success" to success,
                    "timing_ms" to timing,
                    "elapsed_ms" to elapsed,
                    "used_vulkan" to usedVulkan,
                    "peak_memory_mb" to peakMemory,
                    "cancelled" to cancelled,
                    "fallback_used" to fallbackUsed,
                    "fallback_cause" to fallbackCause?.name?.lowercase(),
                    "duration_ms_vulkan" to durationVulkan,
                    "duration_ms_cpu" to durationCpu,
                    "tile_used" to telemetry.tileUsed,
                    "tile_size" to telemetry.tileSize,
                    "tile_overlap" to telemetry.tileOverlap,
                    "tiles_total" to telemetry.tilesTotal,
                    "tiles_completed" to telemetry.tilesCompleted,
                    "seam_max_delta" to telemetry.seamMaxDelta,
                    "seam_mean_delta" to telemetry.seamMeanDelta,
                    "gpu_alloc_retry_count" to telemetry.gpuAllocRetryCount,
                ) + fullCompleteMetadata,
            )

            FullResult(
                bitmap = resultBitmap,
                timingMs = timing,
                usedVulkan = usedVulkan,
                peakMemoryMb = peakMemory,
                cancelled = cancelled,
                fallbackUsed = fallbackUsed,
                fallbackCause = fallbackCause,
                durationMsVulkan = durationVulkan,
                durationMsCpu = durationCpu,
                delegateUsed = telemetry.delegateUsed,
                forceCpuReason = lastForceCpuReason,
                tileUsed = telemetry.tileUsed,
                tileSize = telemetry.tileSize,
                tileOverlap = telemetry.tileOverlap,
                tilesTotal = telemetry.tilesTotal,
                tilesCompleted = telemetry.tilesCompleted,
                seamMaxDelta = telemetry.seamMaxDelta,
                seamMeanDelta = telemetry.seamMeanDelta,
                gpuAllocRetryCount = telemetry.gpuAllocRetryCount,
            )
        } finally {
            activeOperations.decrementAndGet()
        }
    }

    suspend fun cancel() = withContext(dispatcher) {
        if (initializationFlag.get() != INITIALIZED) {
            return@withContext
        }

        EnhanceLogging.logEvent("native_cancel")
        nativeCancel(nativeHandle)
    }

    suspend fun release() = withContext(dispatcher) {
        if (!initializationFlag.compareAndSet(INITIALIZED, RELEASED)) {
            return@withContext
        }

        while (activeOperations.get() > 0) {
            kotlinx.coroutines.delay(10)
        }

        EnhanceLogging.logEvent("native_release", mapOf("handle" to nativeHandle))
        nativeRelease(nativeHandle)
        nativeHandle = 0L
        lastForceCpuReason = null
        lastForceCpuFlag = false
        lastDelegatePlan = DELEGATE_PLAN_CPU
        lastDelegateAvailable = DELEGATE_CPU_ONLY
        lastDelegateUsed = DELEGATE_CPU
        lastVulkanAvailable = false
    }

    fun isInitialized(): Boolean = initializationFlag.get() == INITIALIZED

    private fun checkInitialized() {
        val state = initializationFlag.get()
        if (state != INITIALIZED) {
            throw IllegalStateException("Контроллер не инициализирован (state=$state)")
        }
    }

    private external fun nativeInit(
        assetManager: AssetManager,
        modelsDir: String,
        zeroDceParamChecksum: String,
        zeroDceBinChecksum: String,
        restormerParamChecksum: String,
        restormerBinChecksum: String,
        previewProfile: Int,
        forceCpu: Boolean,
    ): Long

    private external fun nativeRunPreview(
        handle: Long,
        bitmap: Bitmap,
        strength: Float,
    ): NativeRunTelemetry

    private external fun nativeRunFull(
        handle: Long,
        sourceBitmap: Bitmap,
        strength: Float,
        outputBitmap: Bitmap,
    ): NativeRunTelemetry

    private external fun nativeCancel(handle: Long)

    private external fun nativeRelease(handle: Long)

    private external fun nativeIsGpuDelegateAvailable(handle: Long): Boolean

    companion object {
        private const val LOG_TAG = "NativeEnhanceController"
        private const val LIBRARY_NAME = "kotopogoda_enhance"

        private const val UNINITIALIZED = 0
        private const val INITIALIZING = 1
        private const val INITIALIZED = 2
        private const val INITIALIZATION_FAILED = 3
        private const val RELEASED = 4

        private const val BACKEND_ID = "ncnn_cpu"
        private const val BACKEND_PRECISION = "fp16"
        private const val NATIVE_TILE_SIZE = 384
        private const val NATIVE_TILE_OVERLAP = 64
        private const val DELEGATE_CPU = "cpu"
        private const val DELEGATE_GPU = "gpu"
        private const val DELEGATE_CPU_ONLY = "cpu_only"
        private const val DELEGATE_PLAN_CPU = "cpu"
        private const val DELEGATE_PLAN_GPU = "gpu_with_cpu_fallback"

        @JvmStatic
        private external fun nativeConsumeIntegrityFailure(): Array<String>?

        fun loadLibrary() {
            try {
                System.loadLibrary(LIBRARY_NAME)
                Timber.tag(LOG_TAG).i("Нативная библиотека %s загружена", LIBRARY_NAME)
            } catch (error: UnsatisfiedLinkError) {
                Timber.tag(LOG_TAG).e(error, "Не удалось загрузить нативную библиотеку %s", LIBRARY_NAME)
                throw error
            }
        }

        private const val ENV_FORCE_CPU = "ENHANCE_FORCE_CPU"
        @Volatile
        private var forceCpuOverride: Boolean? = null

        fun setForceCpuOverride(enabled: Boolean?) {
            forceCpuOverride = enabled
        }

        fun isForceCpuForced(): Boolean {
            val override = forceCpuOverride
            if (override != null) {
                return override
            }
            return isForceCpuForcedByEnv()
        }

        fun isForceCpuForcedByUser(): Boolean = forceCpuOverride == true

        fun isForceCpuForcedByEnv(): Boolean {
            val envValue = System.getenv(ENV_FORCE_CPU)
            return envValue == "1"
        }
    }

    private fun delegateSnapshotPayload(): Map<String, Any?> = mapOf(
        "backend" to BACKEND_ID,
        "backend_precision" to BACKEND_PRECISION,
        "tile_default" to NATIVE_TILE_SIZE,
        "tile_overlap_default" to NATIVE_TILE_OVERLAP,
        "delegate_plan" to lastDelegatePlan,
        "delegate_available" to lastDelegateAvailable,
        "delegate_used" to lastDelegateUsed,
        "force_cpu" to lastForceCpuFlag,
        "force_cpu_reason" to lastForceCpuReason,
        "vulkan_available" to lastVulkanAvailable,
    )

    private fun consumeIntegrityFailure(): IntegrityFailure? {
        val payload = nativeConsumeIntegrityFailure() ?: return null
        if (payload.size < 3) return null
        return IntegrityFailure(
            filePath = payload[0],
            expectedChecksum = payload[1],
            actualChecksum = payload[2],
        )
    }

    private fun logIntegrityFailure(failure: IntegrityFailure, params: InitParams) {
        Timber.tag(LOG_TAG).e(
            "Обнаружено повреждение модели %s: expected=%s actual=%s",
            failure.filePath,
            failure.expectedChecksum,
            failure.actualChecksum,
        )
        EnhanceLogging.logEvent(
            "native_model_integrity_failure",
            mapOf(
                "file" to failure.filePath,
                "expected" to failure.expectedChecksum,
                "actual" to failure.actualChecksum,
                "models_dir" to params.modelsDir.absolutePath,
                "preview_profile" to params.previewProfile.name,
            ),
        )
    }
}
