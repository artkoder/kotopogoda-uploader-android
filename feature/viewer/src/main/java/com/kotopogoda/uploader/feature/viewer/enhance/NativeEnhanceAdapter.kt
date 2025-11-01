package com.kotopogoda.uploader.feature.viewer.enhance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.kotopogoda.uploader.core.data.upload.UploadEnhancementInfo
import com.kotopogoda.uploader.core.data.upload.UploadEnhancementMetrics
import com.kotopogoda.uploader.core.settings.PreviewQuality
import com.kotopogoda.uploader.feature.viewer.di.RestormerChecksum
import com.kotopogoda.uploader.feature.viewer.di.ZeroDceChecksum
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeEnhanceAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: NativeEnhanceController,
    @ZeroDceChecksum private val zeroDceChecksum: String,
    @RestormerChecksum private val restormerChecksum: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private var isInitialized = false
    private var cachedPreviewBitmap: Bitmap? = null
    private var cachedFullBitmap: Bitmap? = null
    private var currentPhotoPath: String? = null
    private var currentStrength: Float = 0f
    private var previewResult: NativeEnhanceController.PreviewResult? = null

    suspend fun initialize(previewQuality: PreviewQuality) = withContext(dispatcher) {
        if (isInitialized) {
            return@withContext
        }

        val modelsDir = File(context.filesDir, "models")
        val profile = when (previewQuality) {
            PreviewQuality.BALANCED -> NativeEnhanceController.PreviewProfile.BALANCED
            PreviewQuality.QUALITY -> NativeEnhanceController.PreviewProfile.QUALITY
        }

        val params = NativeEnhanceController.InitParams(
            assetManager = context.assets,
            modelsDir = modelsDir,
            zeroDceChecksum = zeroDceChecksum,
            restormerChecksum = restormerChecksum,
            previewProfile = profile,
        )

        controller.initialize(params)
        isInitialized = true
        Timber.tag(TAG).i("NativeEnhanceAdapter инициализирован с профилем %s", previewQuality)
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

        try {
            val result = controller.runPreview(
                sourceBitmap = sourceBitmap,
                strength = strength,
                onProgress = { info ->
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

        try {
            val result = controller.runFull(
                sourceBitmap = sourceBitmap,
                strength = strength,
                outputFile = outputFile,
                quality = 95,
                onProgress = { info ->
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
        val metrics = UploadEnhancementMetrics(
            lMean = 0.5f,
            pDark = 0.3f,
            bSharpness = 0.7f,
            nNoise = 0.2f,
        )

        return UploadEnhancementInfo(
            strength = strength,
            delegate = if (fullResult?.usedVulkan == true) "vulkan" else "cpu",
            metrics = metrics,
            fileSize = outputFile.length(),
            previewTimingMs = previewResult?.timingMs,
            fullTimingMs = fullResult?.timingMs,
            usedVulkan = fullResult?.usedVulkan,
            peakMemoryMb = fullResult?.peakMemoryMb,
            cancelled = fullResult?.cancelled,
        )
    }

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
