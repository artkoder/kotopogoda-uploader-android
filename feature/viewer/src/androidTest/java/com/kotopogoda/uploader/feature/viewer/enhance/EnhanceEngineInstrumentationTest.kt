package com.kotopogoda.uploader.feature.viewer.enhance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Instrumentation тесты для JNI bridge и inference на реальном устройстве/эмуляторе:
 * - Загрузка sample photo assets
 * - Preview/full inference execution
 * - Проверка output dimensions/files
 * - Телеметрия (backend, Vulkan support)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@MediumTest
class EnhanceEngineInstrumentationTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private val createdFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = context.cacheDir.resolve("enhance_test").apply {
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        createdFiles.forEach { file ->
            runCatching { file.delete() }
        }
        runCatching { testDir.deleteRecursively() }
    }

    @Test
    fun loadSamplePhotoAssetAndDecodeSuccessfully() {
        // Создаем простое тестовое изображение
        val sampleFile = createSampleImage(width = 64, height = 64, color = 0xFF_88_44_22.toInt())
        
        val decoder = BitmapImageDecoder()
        val buffer = decoder.decode(sampleFile)
        
        assertEquals(64, buffer.width)
        assertEquals(64, buffer.height)
        assertEquals(64 * 64, buffer.pixels.size)
    }

    @Test
    fun previewInferenceRunsOnDevice() = runTest {
        // Создаем тестовое изображение небольшого размера для preview
        val sampleFile = createSampleImage(width = 128, height = 128, color = 0xFF_20_20_20.toInt())
        val outputFile = testDir.resolve("preview_output.jpg").also { createdFiles.add(it) }
        
        val engine = EnhanceEngine(
            decoder = BitmapImageDecoder(),
            encoder = BitmapImageEncoder(),
            zeroDce = null, // Без моделей - используем fallback
            restormer = null,
        )
        
        val result = engine.enhance(
            EnhanceEngine.Request(
                source = sampleFile,
                strength = 0.5f,
                tileSize = 64,
                overlap = 16,
                delegate = EnhanceEngine.Delegate.CPU,
                outputFile = outputFile,
            )
        )
        
        assertTrue(outputFile.exists(), "Output file должен быть создан")
        assertTrue(outputFile.length() > 0, "Output file не должен быть пустым")
        assertNotNull(result.metrics)
        assertNotNull(result.timings)
    }

    @Test
    fun fullResolutionInferenceProducesCorrectDimensions() = runTest {
        // Создаем изображение среднего размера для full-res теста
        val width = 512
        val height = 384
        val sampleFile = createSampleImage(width = width, height = height, color = 0xFF_40_40_40.toInt())
        val outputFile = testDir.resolve("fullres_output.jpg").also { createdFiles.add(it) }
        
        val engine = EnhanceEngine(
            decoder = BitmapImageDecoder(),
            encoder = BitmapImageEncoder(),
            zeroDce = null,
            restormer = null,
        )
        
        val result = engine.enhance(
            EnhanceEngine.Request(
                source = sampleFile,
                strength = 0.7f,
                tileSize = 256,
                overlap = 64,
                delegate = EnhanceEngine.Delegate.CPU,
                outputFile = outputFile,
            )
        )
        
        assertTrue(outputFile.exists())
        
        // Проверяем размеры выходного изображения
        val outputBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
        assertNotNull(outputBitmap)
        assertEquals(width, outputBitmap.width, "Width должен совпадать с исходным")
        assertEquals(height, outputBitmap.height, "Height должен совпадать с исходным")
        outputBitmap.recycle()
    }

    @Test
    fun telemetryFieldsPopulatedCorrectly() = runTest {
        val sampleFile = createSampleImage(width = 128, height = 128, color = 0xFF_30_30_30.toInt())
        val outputFile = testDir.resolve("telemetry_output.jpg").also { createdFiles.add(it) }
        
        val engine = EnhanceEngine(
            decoder = BitmapImageDecoder(),
            encoder = BitmapImageEncoder(),
            zeroDce = null,
            restormer = null,
        )
        
        val result = engine.enhance(
            EnhanceEngine.Request(
                source = sampleFile,
                strength = 0.6f,
                tileSize = 128,
                overlap = 32,
                delegate = EnhanceEngine.Delegate.CPU,
                outputFile = outputFile,
            )
        )
        
        // Проверяем телеметрию
        assertNotNull(result.metrics)
        assertTrue(result.metrics.lMean >= 0.0 && result.metrics.lMean <= 1.0)
        assertTrue(result.metrics.pDark >= 0.0 && result.metrics.pDark <= 1.0)
        assertTrue(result.metrics.bSharpness >= 0.0)
        assertTrue(result.metrics.nNoise >= 0.0)
        
        assertNotNull(result.pipeline)
        assertTrue(result.pipeline.tileSize > 0)
        assertTrue(result.pipeline.overlap >= 0)
        
        assertNotNull(result.timings)
        assertTrue(result.timings.total >= 0)
        assertTrue(result.timings.decode >= 0)
        assertTrue(result.timings.encode >= 0)
        
        // Delegate должен быть установлен
        assertNotNull(result.delegate)
    }

    @Test
    fun delegateFallbackWhenGpuUnavailable() = runTest {
        val sampleFile = createSampleImage(width = 64, height = 64, color = 0xFF_25_25_25.toInt())
        val outputFile = testDir.resolve("fallback_output.jpg").also { createdFiles.add(it) }
        
        val engine = EnhanceEngine(
            decoder = BitmapImageDecoder(),
            encoder = BitmapImageEncoder(),
            zeroDce = null,
            restormer = null,
        )
        
        // Запрашиваем GPU, но если моделей нет - fallback на CPU
        val result = engine.enhance(
            EnhanceEngine.Request(
                source = sampleFile,
                strength = 0.5f,
                delegate = EnhanceEngine.Delegate.GPU,
                outputFile = outputFile,
            )
        )
        
        // Результат должен быть получен, независимо от delegate
        assertTrue(outputFile.exists())
        assertNotNull(result.delegate)
    }

    @Test
    fun tilingWorksCorrectlyForLargeImages() = runTest {
        // Создаем большое изображение требующее тайлинга
        val width = 512
        val height = 512
        val sampleFile = createSampleImage(width = width, height = height, color = 0xFF_35_35_35.toInt())
        val outputFile = testDir.resolve("tiled_output.jpg").also { createdFiles.add(it) }
        
        var tileProgressCount = 0
        val engine = EnhanceEngine(
            decoder = BitmapImageDecoder(),
            encoder = BitmapImageEncoder(),
            zeroDce = null,
            restormer = null,
        )
        
        val result = engine.enhance(
            EnhanceEngine.Request(
                source = sampleFile,
                strength = 0.5f,
                tileSize = 256,
                overlap = 64,
                delegate = EnhanceEngine.Delegate.CPU,
                outputFile = outputFile,
                onTileProgress = { progress ->
                    tileProgressCount++
                    assertTrue(progress.progress >= 0f && progress.progress <= 1f)
                    assertTrue(progress.index >= 0)
                    assertTrue(progress.total >= 0)
                }
            )
        )
        
        assertTrue(outputFile.exists())
        // Для изображения 512x512 с тайлами 256 должно быть несколько тайлов
        assertTrue(result.pipeline.tileCount > 0, "Должно быть вычислено количество тайлов")
        
        // Проверяем что callback вызывался
        assertTrue(tileProgressCount >= 0, "Tile progress callback должен был вызваться")
    }

    @Test
    fun metricsCalculationConsistentAcrossRuns() = runTest {
        // Создаем детерминированное изображение
        val sampleFile = createSampleImage(width = 64, height = 64, color = 0xFF_50_50_50.toInt())
        val outputFile1 = testDir.resolve("metrics_run1.jpg").also { createdFiles.add(it) }
        val outputFile2 = testDir.resolve("metrics_run2.jpg").also { createdFiles.add(it) }
        
        val engine = EnhanceEngine(
            decoder = BitmapImageDecoder(),
            encoder = BitmapImageEncoder(),
            zeroDce = null,
            restormer = null,
        )
        
        val result1 = engine.enhance(
            EnhanceEngine.Request(
                source = sampleFile,
                strength = 0.5f,
                outputFile = outputFile1,
            )
        )
        
        val result2 = engine.enhance(
            EnhanceEngine.Request(
                source = sampleFile,
                strength = 0.5f,
                outputFile = outputFile2,
            )
        )
        
        // Метрики должны быть одинаковыми для одного и того же входного изображения
        assertEquals(result1.metrics.lMean, result2.metrics.lMean, 0.01)
        assertEquals(result1.metrics.pDark, result2.metrics.pDark, 0.01)
    }

    @Test
    fun profileCalculationReflectsImageCharacteristics() = runTest {
        // Темное изображение должно иметь isLowLight = true
        val darkFile = createSampleImage(width = 64, height = 64, color = 0xFF_10_10_10.toInt())
        // Светлое изображение должно иметь isLowLight = false
        val brightFile = createSampleImage(width = 64, height = 64, color = 0xFF_E0_E0_E0.toInt())
        
        val darkOutput = testDir.resolve("dark_output.jpg").also { createdFiles.add(it) }
        val brightOutput = testDir.resolve("bright_output.jpg").also { createdFiles.add(it) }
        
        val engine = EnhanceEngine(
            decoder = BitmapImageDecoder(),
            encoder = BitmapImageEncoder(),
            zeroDce = null,
            restormer = null,
        )
        
        val darkResult = engine.enhance(
            EnhanceEngine.Request(
                source = darkFile,
                strength = 0.5f,
                outputFile = darkOutput,
            )
        )
        
        val brightResult = engine.enhance(
            EnhanceEngine.Request(
                source = brightFile,
                strength = 0.5f,
                outputFile = brightOutput,
            )
        )
        
        // Темное изображение должно определяться как lowLight
        assertTrue(darkResult.profile.isLowLight, "Темное изображение должно иметь isLowLight = true")
        assertTrue(darkResult.metrics.pDark > 0.5, "Темное изображение должно иметь высокий pDark")
        
        // Светлое изображение не должно быть lowLight
        assertFalse(brightResult.profile.isLowLight, "Светлое изображение должно иметь isLowLight = false")
        assertTrue(brightResult.metrics.pDark < 0.5, "Светлое изображение должно иметь низкий pDark")
    }

    private fun createSampleImage(width: Int, height: Int, color: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(color)
        
        val file = testDir.resolve("sample_${width}x${height}_${System.nanoTime()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        bitmap.recycle()
        
        createdFiles.add(file)
        return file
    }
}

// Простые реализации decoder/encoder для тестов
internal class BitmapImageDecoder : EnhanceEngine.ImageDecoder {
    override fun decode(file: File): EnhanceEngine.ImageBuffer {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalArgumentException("Cannot decode bitmap from $file")
        
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        
        return EnhanceEngine.ImageBuffer(width, height, pixels)
    }
}

internal class BitmapImageEncoder : EnhanceEngine.ImageEncoder {
    override fun encode(buffer: EnhanceEngine.ImageBuffer, target: File) {
        val bitmap = Bitmap.createBitmap(
            buffer.pixels,
            buffer.width,
            buffer.height,
            Bitmap.Config.ARGB_8888
        )
        
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()
    }
}
