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

    data class InitParams(
        val assetManager: AssetManager,
        val modelsDir: File,
        val zeroDceChecksum: String,
        val restormerChecksum: String,
        val previewProfile: PreviewProfile,
    )

    data class PreviewResult(
        val success: Boolean,
        val timingMs: Long,
        val usedVulkan: Boolean,
        val peakMemoryMb: Float,
    )

    data class FullResult(
        val bitmap: Bitmap,
        val timingMs: Long,
        val usedVulkan: Boolean,
        val peakMemoryMb: Float,
        val cancelled: Boolean,
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
                params.zeroDceChecksum,
                params.restormerChecksum,
                params.previewProfile.ordinal,
            )

            if (handle == 0L) {
                throw IllegalStateException("Нативная инициализация вернула нулевой handle")
            }

            nativeHandle = handle
            initializationFlag.set(INITIALIZED)

            Timber.tag(LOG_TAG).i(
                "Нативный контроллер инициализирован: handle=%d profile=%s",
                handle,
                params.previewProfile,
            )

            EnhanceLogging.logEvent(
                "native_controller_init",
                mapOf(
                    "handle" to handle,
                    "models_dir" to params.modelsDir.absolutePath,
                    "preview_profile" to params.previewProfile.name,
                    "zero_dce_checksum" to params.zeroDceChecksum.take(8),
                    "restormer_checksum" to params.restormerChecksum.take(8),
                ),
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
            EnhanceLogging.logEvent(
                "native_preview_start",
                mapOf(
                    "strength" to strength,
                    "width" to sourceBitmap.width,
                    "height" to sourceBitmap.height,
                ),
            )

            val startTime = System.currentTimeMillis()
            val result = nativeRunPreview(nativeHandle, sourceBitmap, strength)
            val elapsed = System.currentTimeMillis() - startTime

            val success = result[0] > 0
            val timing = result[1]
            val usedVulkan = result[2] > 0
            val peakMemory = result[3].toFloat() / 1024f

            EnhanceLogging.logEvent(
                "native_preview_complete",
                mapOf(
                    "success" to success,
                    "timing_ms" to timing,
                    "elapsed_ms" to elapsed,
                    "used_vulkan" to usedVulkan,
                    "peak_memory_mb" to peakMemory,
                ),
            )

            PreviewResult(
                success = success,
                timingMs = timing,
                usedVulkan = usedVulkan,
                peakMemoryMb = peakMemory,
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
            EnhanceLogging.logEvent(
                "native_full_start",
                mapOf(
                    "strength" to strength,
                    "width" to sourceBitmap.width,
                    "height" to sourceBitmap.height,
                    "output_file" to outputFile.absolutePath,
                    "quality" to quality,
                ),
            )

            val startTime = System.currentTimeMillis()
            val resultBitmap = Bitmap.createBitmap(
                sourceBitmap.width,
                sourceBitmap.height,
                Bitmap.Config.ARGB_8888,
            )

            val result = nativeRunFull(nativeHandle, sourceBitmap, strength, resultBitmap)
            val elapsed = System.currentTimeMillis() - startTime

            val success = result[0] > 0
            val timing = result[1]
            val usedVulkan = result[2] > 0
            val peakMemory = result[3].toFloat() / 1024f
            val cancelled = result[4] > 0

            EnhanceLogging.logEvent(
                "native_full_complete",
                mapOf(
                    "success" to success,
                    "timing_ms" to timing,
                    "elapsed_ms" to elapsed,
                    "used_vulkan" to usedVulkan,
                    "peak_memory_mb" to peakMemory,
                    "cancelled" to cancelled,
                ),
            )

            FullResult(
                bitmap = resultBitmap,
                timingMs = timing,
                usedVulkan = usedVulkan,
                peakMemoryMb = peakMemory,
                cancelled = cancelled,
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
        zeroDceChecksum: String,
        restormerChecksum: String,
        previewProfile: Int,
    ): Long

    private external fun nativeRunPreview(
        handle: Long,
        bitmap: Bitmap,
        strength: Float,
    ): LongArray

    private external fun nativeRunFull(
        handle: Long,
        sourceBitmap: Bitmap,
        strength: Float,
        outputBitmap: Bitmap,
    ): LongArray

    private external fun nativeCancel(handle: Long)

    private external fun nativeRelease(handle: Long)

    companion object {
        private const val LOG_TAG = "NativeEnhanceController"
        private const val LIBRARY_NAME = "kotopogoda_enhance"

        private const val UNINITIALIZED = 0
        private const val INITIALIZING = 1
        private const val INITIALIZED = 2
        private const val INITIALIZATION_FAILED = 3
        private const val RELEASED = 4

        fun loadLibrary() {
            try {
                System.loadLibrary(LIBRARY_NAME)
                Timber.tag(LOG_TAG).i("Нативная библиотека %s загружена", LIBRARY_NAME)
            } catch (error: UnsatisfiedLinkError) {
                Timber.tag(LOG_TAG).e(error, "Не удалось загрузить нативную библиотеку %s", LIBRARY_NAME)
                throw error
            }
        }
    }
}
