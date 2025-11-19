package com.kotopogoda.uploader.feature.viewer.enhance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.kotopogoda.uploader.core.data.upload.UploadEnhancementInfo
import com.kotopogoda.uploader.core.data.upload.UploadEnhancementMetrics
import com.kotopogoda.uploader.core.settings.PreviewQuality
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine.ModelBackend
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine.ModelUsage
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceLogging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class NativeEnhanceAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: NativeEnhanceController,
    private val modelsInstaller: EnhancerModelsInstaller,
    @Named("zeroDceChecksums") private val zeroDceChecksums: NativeEnhanceController.ModelChecksums,
    @Named("restormerChecksums") private val restormerChecksums: NativeEnhanceController.ModelChecksums,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var isInitialized = false
    private var cachedPreviewBitmap: Bitmap? = null
    private var cachedFullBitmap: Bitmap? = null
    private var currentPhotoPath: String? = null
    private var currentStrength: Float = 0f
    private var previewResult: NativeEnhanceController.PreviewResult? = null
    private val crashLoopDetector = NativeEnhanceCrashLoopDetector(context)

    fun isReady(): Boolean = isInitialized && controller.isInitialized()

    fun modelsTelemetry(): EnhanceEngine.ModelsTelemetry = EnhanceEngine.ModelsTelemetry(
        zeroDce = ModelUsage(
            backend = ModelBackend.NCNN,
            checksum = zeroDceChecksums.bin,
            expectedChecksum = zeroDceChecksums.bin,
            checksumOk = true,
        ),
        restormer = ModelUsage(
            backend = ModelBackend.NCNN,
            checksum = restormerChecksums.bin,
            expectedChecksum = restormerChecksums.bin,
            checksumOk = true,
        ),
    )

    suspend fun initialize(previewQuality: PreviewQuality) = withContext(dispatcher) {
        if (isInitialized) {
            return@withContext
        }

        val modelsDir = modelsInstaller.ensureInstalled()
        val profile = when (previewQuality) {
            PreviewQuality.BALANCED -> NativeEnhanceController.PreviewProfile.BALANCED
            PreviewQuality.QUALITY -> NativeEnhanceController.PreviewProfile.QUALITY
        }

        val deviceIsExynos = DeviceGpuPolicy.isExynosSmG99x
        val envForceCpu = NativeEnhanceController.isForceCpuForcedByEnv()
        val userForceCpu = NativeEnhanceController.isForceCpuForcedByUser()
        val crashLoopFlag = crashLoopDetector.isCrashLoopSuspected()
        val effectiveForceCpu = deviceIsExynos || envForceCpu || userForceCpu || crashLoopFlag
        val forceCpuReason = when {
            userForceCpu -> "user_override"
            deviceIsExynos -> DeviceGpuPolicy.forceCpuReason
            crashLoopFlag -> "crash_loop"
            envForceCpu -> "env_override"
            else -> null
        }

        Timber.tag(TAG).i(
            "GPU policy: deviceIsExynos=%s envForce=%s userForce=%s crashLoop=%s -> forceCpu=%s reason=%s",
            deviceIsExynos,
            envForceCpu,
            userForceCpu,
            crashLoopFlag,
            effectiveForceCpu,
            forceCpuReason,
        )

        val params = NativeEnhanceController.InitParams(
            assetManager = context.assets,
            modelsDir = modelsDir,
            zeroDceChecksums = zeroDceChecksums,
            restormerChecksums = restormerChecksums,
            previewProfile = profile,
            forceCpu = effectiveForceCpu,
            forceCpuReason = forceCpuReason,
        )

        controller.initialize(params)
        isInitialized = true
        Timber.tag(TAG).i(
            "NativeEnhanceAdapter инициализирован с профилем %s (forceCpu=%s reason=%s)",
            previewQuality,
            effectiveForceCpu,
            forceCpuReason,
        )
    }

    suspend fun computePreview(
        sourceFile: File,
        strength: Float,
        onProgress: (Float) -> Unit = {},
    ): Boolean = withContext(dispatcher) {
        if (!isInitialized) {
            Timber.tag(TAG).w("Попытка вычисления превью до инициализации")
            return@withContext false
        }

        val isSamePhoto = currentPhotoPath == sourceFile.absolutePath
        val isSameStrength = kotlin.math.abs(currentStrength - strength) < 0.01f

        if (isSamePhoto && cachedPreviewBitmap != null && isSameStrength) {
            Timber.tag(TAG).d("Используем закешированный preview")
            return@withContext true
        }

        if (currentPhotoPath != sourceFile.absolutePath) {
            clearCache()
            currentPhotoPath = sourceFile.absolutePath
        }

        currentStrength = strength

        val sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: return@withContext false

        crashLoopDetector.markEnhanceRunning()
        try {
            val result = controller.runPreview(
                sourceBitmap = sourceBitmap,
                strength = strength,
                onProgress = { info ->
                    EnhanceLogging.logEvent(
                        "native_preview_progress",
                        "progress" to info.progress,
                        "stage" to info.currentStage,
                    )
                    onProgress(info.progress)
                },
            )

            previewResult = result

            if (result.success) {
                cachedPreviewBitmap = sourceBitmap
                return@withContext true
            }

            return@withContext false
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Ошибка вычисления превью")
            return@withContext false
        } finally {
            crashLoopDetector.clearEnhanceRunningFlag()
            if (cachedPreviewBitmap != sourceBitmap) {
                sourceBitmap.recycle()
            }
        }
    }

    suspend fun computeFull(
        sourceFile: File,
        strength: Float,
        outputFile: File,
        exif: ExifInterface? = null,
        onProgress: (Float) -> Unit = {},
    ): UploadEnhancementInfo? = withContext(dispatcher) {
        if (!isInitialized) {
            Timber.tag(TAG).w("Попытка полного вычисления до инициализации")
            return@withContext null
        }

        val isSamePhoto = currentPhotoPath == sourceFile.absolutePath
        if (isSamePhoto && cachedFullBitmap != null) {
            Timber.tag(TAG).d("Используем закешированный full result")
            val outputStream = FileOutputStream(outputFile)
            cachedFullBitmap?.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.close()
            
            copyExif(exif, sourceFile, outputFile)
            
            return@withContext buildEnhancementInfo(strength, outputFile)
        }

        clearFullCache()

        val sourceBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath)
            ?: return@withContext null

        crashLoopDetector.markEnhanceRunning()
        try {
            val result = controller.runFull(
                sourceBitmap = sourceBitmap,
                strength = strength,
                outputFile = outputFile,
                quality = 95,
                onProgress = { info ->
                    EnhanceLogging.logEvent(
                        "native_full_progress",
                        "progress" to info.progress,
                        "stage" to info.currentStage,
                    )
                    onProgress(info.progress)
                },
            )

            if (result.cancelled) {
                Timber.tag(TAG).w("Полное вычисление отменено")
                return@withContext null
            }

            cachedFullBitmap = result.bitmap
            
            val outputStream = FileOutputStream(outputFile)
            result.bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.close()

            copyExif(exif, sourceFile, outputFile)

            return@withContext buildEnhancementInfo(strength, outputFile, result)
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Ошибка полного вычисления")
            return@withContext null
        } finally {
            crashLoopDetector.clearEnhanceRunningFlag()
            if (cachedFullBitmap != sourceBitmap) {
                sourceBitmap.recycle()
            }
        }
    }

    suspend fun cancel() = withContext(dispatcher) {
        controller.cancel()
    }

    suspend fun release() = withContext(dispatcher) {
        clearCache()
        controller.release()
        isInitialized = false
    }

    fun clearCache() {
        cachedPreviewBitmap?.recycle()
        cachedPreviewBitmap = null
        clearFullCache()
        currentPhotoPath = null
        currentStrength = 0f
        previewResult = null
    }

    private fun clearFullCache() {
        cachedFullBitmap?.recycle()
        cachedFullBitmap = null
    }

    private fun buildEnhancementInfo(
        strength: Float,
        outputFile: File,
        fullResult: NativeEnhanceController.FullResult? = null,
    ): UploadEnhancementInfo {
        val metrics = fullResult?.bitmap?.let(::computeMetricsForBitmap)
            ?: cachedFullBitmap?.let(::computeMetricsForBitmap)
            ?: computeMetricsFromFile(outputFile)

        val usedVulkan = fullResult?.usedVulkan ?: previewResult?.usedVulkan
        val fallbackUsed = fullResult?.fallbackUsed ?: previewResult?.fallbackUsed
        val fallbackCause = (fullResult?.fallbackCause ?: previewResult?.fallbackCause)
            ?.name
            ?.lowercase()
        val durationVulkan = fullResult?.durationMsVulkan ?: previewResult?.durationMsVulkan
        val durationCpu = fullResult?.durationMsCpu ?: previewResult?.durationMsCpu

        return UploadEnhancementInfo(
            strength = strength,
            delegate = if (usedVulkan == true) "vulkan" else "cpu",
            metrics = metrics,
            fileSize = outputFile.length(),
            previewTimingMs = previewResult?.timingMs,
            fullTimingMs = fullResult?.timingMs,
            usedVulkan = usedVulkan,
            peakMemoryMb = fullResult?.peakMemoryMb,
            cancelled = fullResult?.cancelled,
            fallbackUsed = fallbackUsed,
            fallbackCause = fallbackCause,
            durationMsVulkan = durationVulkan,
            durationMsCpu = durationCpu,
        )
    }

    private fun computeMetricsFromFile(file: File): UploadEnhancementMetrics {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return emptyMetrics()
        return computeMetricsForBitmap(bitmap).also { bitmap.recycle() }
    }

    private fun computeMetricsForBitmap(bitmap: Bitmap): UploadEnhancementMetrics {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return emptyMetrics()
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = EnhanceEngine.ImageBuffer(width, height, pixels)
        val metrics = EnhanceEngine.MetricsCalculator.calculate(buffer)
        return UploadEnhancementMetrics(
            lMean = metrics.lMean.toFloat(),
            pDark = metrics.pDark.toFloat(),
            bSharpness = metrics.bSharpness.toFloat(),
            nNoise = metrics.nNoise.toFloat(),
        )
    }

    private fun emptyMetrics(): UploadEnhancementMetrics = UploadEnhancementMetrics(
        lMean = 0f,
        pDark = 0f,
        bSharpness = 0f,
        nNoise = 0f,
    )

    private fun copyExif(exif: ExifInterface?, sourceFile: File, outputFile: File) {
        try {
            val sourceExif = exif ?: ExifInterface(sourceFile.absolutePath)
            val destExif = ExifInterface(outputFile.absolutePath)

            val tags = listOf(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
            )

            tags.forEach { tag ->
                sourceExif.getAttribute(tag)?.let { value ->
                    destExif.setAttribute(tag, value)
                }
            }

            destExif.saveAttributes()
        } catch (error: Exception) {
            Timber.tag(TAG).w(error, "Не удалось скопировать EXIF")
        }
    }

    companion object {
        private const val TAG = "NativeEnhanceAdapter"
    }
}
