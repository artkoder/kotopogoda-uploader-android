package com.kotopogoda.uploader.feature.viewer.enhance

import android.graphics.Color
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EnhanceEngineTest {

    @Test
    fun `metrics for black image`() {
        val buffer = EnhanceEngine.ImageBuffer(4, 4, IntArray(16) { argb(0, 0, 0) })
        val metrics = EnhanceEngine.MetricsCalculator.calculate(buffer)
        assertEquals(0.0, metrics.lMean, 1e-6)
        assertEquals(1.0, metrics.pDark, 1e-6)
        assertEquals(0.0, metrics.bSharpness, 1e-6)
        assertEquals(0.0, metrics.nNoise, 1e-6)
    }

    @Test
    fun `metrics detect edges and noise`() {
        val pixels = intArrayOf(
            argb(0, 0, 0), argb(255, 255, 255),
            argb(255, 0, 0), argb(0, 255, 0),
        )
        val buffer = EnhanceEngine.ImageBuffer(2, 2, pixels)
        val metrics = EnhanceEngine.MetricsCalculator.calculate(buffer)
        assertTrue(metrics.lMean in 0.4..0.7)
        assertTrue(metrics.pDark in 0.25..0.75)
        assertTrue("sharpness must stay high", metrics.bSharpness in 0.75..1.0)
        assertTrue("noise must be within calibrated range", metrics.nNoise in 0.9..1.0)
    }

    @Test
    fun `profile curves stay monotonic for target strengths`() {
        val metrics = EnhanceEngine.Metrics(
            lMean = 0.32,
            pDark = 0.42,
            bSharpness = 0.28,
            nNoise = 0.48,
        )
        val profiles = listOf(0, 25, 50, 75, 100)
            .map { strength -> strength to EnhanceEngine.ProfileCalculator.calculate(metrics, strength / 100f) }

        assertTrue("должно определяться низкое освещение", profiles.all { it.second.isLowLight })
        assertNonDecreasing(profiles.map { it.second.kDce }, "kDce")
        assertNonDecreasing(profiles.map { it.second.restormerMix }, "restormerMix")
        assertNonDecreasing(profiles.map { it.second.alphaDetail }, "alphaDetail")
        assertNonDecreasing(profiles.map { it.second.sharpenAmount }, "sharpenAmount")
        assertTrue(profiles.all { (_, profile) -> profile.sharpenRadius in 0.8f..3.2f })
        assertTrue(profiles.all { (_, profile) -> profile.sharpenThreshold in 0.01f..0.12f })
        assertNonDecreasing(profiles.map { it.second.vibranceGain }, "vibranceGain")
        assertNonDecreasing(profiles.map { it.second.saturationGain }, "saturationGain")
    }

    @Test
    fun `profile matches specification samples`() {
        val metrics = EnhanceEngine.Metrics(
            lMean = 0.32,
            pDark = 0.42,
            bSharpness = 0.28,
            nNoise = 0.48,
        )

        val expected = mapOf(
            0 to EnhanceEngine.Profile(
                isLowLight = true,
                kDce = 0.24109091f,
                restormerMix = 0.1344f,
                alphaDetail = 0.2352f,
                sharpenAmount = 0.19418f,
                sharpenRadius = 3.1f,
                sharpenThreshold = 0.012f,
                vibranceGain = 0.20384f,
                saturationGain = 1.0736f,
            ),
            25 to EnhanceEngine.Profile(
                isLowLight = true,
                kDce = 0.31421593f,
                restormerMix = 0.1884f,
                alphaDetail = 0.32095f,
                sharpenAmount = 0.25052688f,
                sharpenRadius = 3.1f,
                sharpenThreshold = 0.012f,
                vibranceGain = 0.29449f,
                saturationGain = 1.120475f,
            ),
            50 to EnhanceEngine.Profile(
                isLowLight = true,
                kDce = 0.47509092f,
                restormerMix = 0.3072f,
                alphaDetail = 0.5096f,
                sharpenAmount = 0.37449f,
                sharpenRadius = 3.1f,
                sharpenThreshold = 0.012f,
                vibranceGain = 0.49392f,
                saturationGain = 1.2236f,
            ),
            75 to EnhanceEngine.Profile(
                isLowLight = true,
                kDce = 0.63596594f,
                restormerMix = 0.426f,
                alphaDetail = 0.69825f,
                sharpenAmount = 0.49845314f,
                sharpenRadius = 3.1f,
                sharpenThreshold = 0.012f,
                vibranceGain = 0.69335f,
                saturationGain = 1.326725f,
            ),
            100 to EnhanceEngine.Profile(
                isLowLight = true,
                kDce = 0.7090909f,
                restormerMix = 0.48f,
                alphaDetail = 0.784f,
                sharpenAmount = 0.5548f,
                sharpenRadius = 3.1f,
                sharpenThreshold = 0.012f,
                vibranceGain = 0.784f,
                saturationGain = 1.3736f,
            ),
        )

        expected.forEach { (strength, expectedProfile) ->
            val actual = EnhanceEngine.ProfileCalculator.calculate(metrics, strength / 100f)
            assertEquals("isLowLight mismatch for strength=$strength", expectedProfile.isLowLight, actual.isLowLight)
            assertEquals("kDce mismatch for strength=$strength", expectedProfile.kDce, actual.kDce, 1e-6f)
            assertEquals("restormerMix mismatch for strength=$strength", expectedProfile.restormerMix, actual.restormerMix, 1e-6f)
            assertEquals("alphaDetail mismatch for strength=$strength", expectedProfile.alphaDetail, actual.alphaDetail, 1e-6f)
            assertEquals("sharpenAmount mismatch for strength=$strength", expectedProfile.sharpenAmount, actual.sharpenAmount, 1e-6f)
            assertEquals("sharpenRadius mismatch for strength=$strength", expectedProfile.sharpenRadius, actual.sharpenRadius, 1e-6f)
            assertEquals("sharpenThreshold mismatch for strength=$strength", expectedProfile.sharpenThreshold, actual.sharpenThreshold, 1e-6f)
            assertEquals("vibranceGain mismatch for strength=$strength", expectedProfile.vibranceGain, actual.vibranceGain, 1e-6f)
            assertEquals("saturationGain mismatch for strength=$strength", expectedProfile.saturationGain, actual.saturationGain, 1e-6f)
        }
    }

    @Test
    fun `sharpen disabled for noisy low strength`() {
        val metrics = EnhanceEngine.Metrics(
            lMean = 0.45,
            pDark = 0.2,
            bSharpness = 0.4,
            nNoise = 0.7,
        )
        val profile = EnhanceEngine.ProfileCalculator.calculate(metrics, 0.35f)
        assertEquals(0f, profile.sharpenAmount, 1e-6f)
    }

    @Test
    fun `sharpen returns when strength grows`() {
        val metrics = EnhanceEngine.Metrics(
            lMean = 0.45,
            pDark = 0.2,
            bSharpness = 0.4,
            nNoise = 0.7,
        )
        val lowStrength = EnhanceEngine.ProfileCalculator.calculate(metrics, 0.35f)
        val highStrength = EnhanceEngine.ProfileCalculator.calculate(metrics, 0.5f)
        assertEquals(0f, lowStrength.sharpenAmount, 1e-6f)
        assertTrue(highStrength.sharpenAmount > 0f)
    }

    @Test
    fun `zero-dce triggers only on low light`() = runTest {
        val darkPixels = IntArray(16) { argb(15, 15, 15) }
        val brightPixels = IntArray(16) { argb(240, 240, 240) }
        val decoder = QueueDecoder(
            listOf(
                EnhanceEngine.ImageBuffer(4, 4, darkPixels),
                EnhanceEngine.ImageBuffer(4, 4, brightPixels),
            ),
        )
        val encoder = RecordingEncoder()
        val zeroDce = TrackingZeroDce()
        val engine = EnhanceEngine(
            decoder = decoder,
            encoder = encoder,
            zeroDce = zeroDce,
            restormer = null,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val input = File.createTempFile("input", ".jpg")
        val output = File.createTempFile("output", ".jpg")

        engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
                delegate = EnhanceEngine.Delegate.GPU,
            ),
        )
        assertEquals(1, zeroDce.calls.size)
        assertEquals(EnhanceEngine.Delegate.GPU, zeroDce.calls.single().delegate)
        val firstBuffer = encoder.lastBuffer
        assertNotNull(firstBuffer)
        val luma = firstBuffer.pixels.map { pixel ->
            luminance(
                Color.red(pixel) / 255f,
                Color.green(pixel) / 255f,
                Color.blue(pixel) / 255f,
            )
        }.average()
        assertTrue("zero-dce should brighten", luma > 0.1)

        engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
            ),
        )
        assertEquals(1, zeroDce.calls.size)
    }

    @Test
    fun `restormer invoked only when mix positive`() = runTest {
        val noisyPixels = IntArray(16) { index -> if (index % 2 == 0) argb(255, 0, 0) else argb(0, 0, 255) }
        val cleanPixels = IntArray(16) { argb(220, 220, 220) }
        val decoder = QueueDecoder(
            listOf(
                EnhanceEngine.ImageBuffer(4, 4, noisyPixels),
                EnhanceEngine.ImageBuffer(4, 4, cleanPixels),
            ),
        )
        val encoder = RecordingEncoder()
        val restormer = TrackingRestormer()
        val engine = EnhanceEngine(
            decoder = decoder,
            encoder = encoder,
            zeroDce = null,
            restormer = restormer,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val input = File.createTempFile("input", ".jpg")
        val output = File.createTempFile("output", ".jpg")

        engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
                tileSize = 2,
                overlap = 1,
            ),
        )
        assertTrue("restormer should be used for noisy image", restormer.calls > 0)

        val callsAfterNoisy = restormer.calls
        engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
            ),
        )
        assertEquals("restormer should not run on clean image", callsAfterNoisy, restormer.calls)
    }

    @Test
    fun `pipeline reports tile telemetry and restormer fallback`() = runTest {
        val noisyPixels = IntArray(16) { index -> if (index % 2 == 0) argb(255, 0, 0) else argb(0, 0, 255) }
        val decoder = QueueDecoder(listOf(EnhanceEngine.ImageBuffer(4, 4, noisyPixels)))
        val encoder = RecordingEncoder()
        val restormer = FallbackRestormer()
        val engine = EnhanceEngine(
            decoder = decoder,
            encoder = encoder,
            zeroDce = null,
            restormer = restormer,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val input = File.createTempFile("input", ".jpg")
        val output = File.createTempFile("output", ".jpg")

        val result = engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
                delegate = EnhanceEngine.Delegate.GPU,
                tileSize = 256,
                overlap = 200,
            ),
        )

        val pipeline = result.pipeline
        assertEquals(256, pipeline.tileSizeActual)
        assertEquals(128, pipeline.overlapActual)
        assertEquals(256, pipeline.mixingWindow)
        assertEquals(256, pipeline.mixingWindowActual)
        assertTrue("restormer should be used", pipeline.restormerApplied)
        assertTrue("restormer fallback must be recorded", pipeline.restormerDelegateFallback)

        input.delete()
        output.delete()
    }

    @Test
    fun `pipeline reports zero-dce delegate fallback`() = runTest {
        val darkPixels = IntArray(16) { argb(10, 10, 10) }
        val decoder = QueueDecoder(listOf(EnhanceEngine.ImageBuffer(4, 4, darkPixels)))
        val encoder = RecordingEncoder()
        val zeroDce = FallbackZeroDce()
        val engine = EnhanceEngine(
            decoder = decoder,
            encoder = encoder,
            zeroDce = zeroDce,
            restormer = null,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val input = File.createTempFile("input", ".jpg")
        val output = File.createTempFile("output", ".jpg")

        val result = engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
                delegate = EnhanceEngine.Delegate.GPU,
            ),
        )

        val pipeline = result.pipeline
        assertTrue("zero-dce must be applied for dark image", pipeline.zeroDceApplied)
        assertTrue("zero-dce fallback must be reported", pipeline.zeroDceDelegateFallback)

        input.delete()
        output.delete()
    }

    @Test
    fun `hann window tapers to zero at edges`() {
        assertClose(0f, hannWeight(position = 0, size = 512, overlap = 64), 1e-6f)
        assertClose(0.5f, hannWeight(position = 32, size = 512, overlap = 64), 1e-3f)
        assertClose(1f, hannWeight(position = 128, size = 512, overlap = 64), 1e-6f)
        assertClose(
            hannWeight(position = 1, size = 33, overlap = 32),
            hannWeight(position = 31, size = 33, overlap = 32),
            1e-6f,
        )
    }

    @Test
    fun `models telemetry includes backend and checksums`() = runTest {
        val pixels = IntArray(16) { argb(30, 30, 30) }
        val decoder = QueueDecoder(listOf(EnhanceEngine.ImageBuffer(4, 4, pixels)))
        val encoder = RecordingEncoder()
        val zeroDce = TrackingZeroDce()
        val restormer = TrackingRestormer()
        val expectedChecksums = EnhanceEngine.ExpectedChecksums(
            zeroDce = "zero-dce-test",
            restormer = "restormer-test",
        )
        val engine = EnhanceEngine(
            decoder = decoder,
            encoder = encoder,
            zeroDce = zeroDce,
            restormer = restormer,
            dispatcher = StandardTestDispatcher(testScheduler),
            expectedChecksums = expectedChecksums,
        )
        val input = File.createTempFile("input", ".jpg")
        val output = File.createTempFile("output", ".jpg")

        val result = engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
                tileSize = 2,
                overlap = 1,
            ),
        )

        val models = result.models
        assertNotNull(models.zeroDce)
        assertEquals(EnhanceEngine.ModelBackend.TFLITE, models.zeroDce?.backend)
        assertEquals("zero-dce-test", models.zeroDce?.checksum)
        assertEquals("zero-dce-test", models.zeroDce?.expectedChecksum)
        assertEquals(true, models.zeroDce?.checksumOk)

        assertNotNull(models.restormer)
        assertEquals(EnhanceEngine.ModelBackend.TFLITE, models.restormer?.backend)
        assertEquals("restormer-test", models.restormer?.checksum)
        assertEquals("restormer-test", models.restormer?.expectedChecksum)
        assertEquals(true, models.restormer?.checksumOk)

        input.delete()
        output.delete()
    }

    @Test
    fun `checksum mismatch reported correctly`() = runTest {
        val pixels = IntArray(16) { argb(30, 30, 30) }
        val decoder = QueueDecoder(listOf(EnhanceEngine.ImageBuffer(4, 4, pixels)))
        val encoder = RecordingEncoder()
        val zeroDce = TrackingZeroDce()
        val expectedChecksums = EnhanceEngine.ExpectedChecksums(
            zeroDce = "different-checksum",
        )
        val engine = EnhanceEngine(
            decoder = decoder,
            encoder = encoder,
            zeroDce = zeroDce,
            restormer = null,
            dispatcher = StandardTestDispatcher(testScheduler),
            expectedChecksums = expectedChecksums,
        )
        val input = File.createTempFile("input", ".jpg")
        val output = File.createTempFile("output", ".jpg")

        val result = engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
            ),
        )

        val models = result.models
        assertNotNull(models.zeroDce)
        assertEquals("zero-dce-test", models.zeroDce?.checksum)
        assertEquals("different-checksum", models.zeroDce?.expectedChecksum)
        assertEquals(false, models.zeroDce?.checksumOk)

        input.delete()
        output.delete()
    }

    @Test
    fun `tile progress callback receives updates during processing`() = runTest {
        val noisyPixels = IntArray(16) { index -> if (index % 2 == 0) argb(255, 0, 0) else argb(0, 0, 255) }
        val decoder = QueueDecoder(listOf(EnhanceEngine.ImageBuffer(4, 4, noisyPixels)))
        val encoder = RecordingEncoder()
        val restormer = TrackingRestormer()
        val engine = EnhanceEngine(
            decoder = decoder,
            encoder = encoder,
            zeroDce = null,
            restormer = restormer,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val input = File.createTempFile("input", ".jpg")
        val output = File.createTempFile("output", ".jpg")

        val progressUpdates = mutableListOf<EnhanceEngine.TileProgress>()
        engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
                tileSize = 2,
                overlap = 1,
                onTileProgress = { progress ->
                    progressUpdates.add(progress)
                },
            ),
        )

        assertTrue(progressUpdates.isNotEmpty(), "должны быть обновления прогресса")
        progressUpdates.forEach { progress ->
            assertTrue(progress.progress >= 0f && progress.progress <= 1f, "прогресс должен быть в диапазоне [0, 1]")
            assertTrue(progress.index >= 0, "индекс тайла должен быть неотрицательным")
            assertTrue(progress.total >= 0, "общее количество тайлов должно быть неотрицательным")
        }

        input.delete()
        output.delete()
    }

    @Test
    fun `pipeline reports seamless blending metrics`() = runTest {
        val noisyPixels = IntArray(64) { index -> if (index % 3 == 0) argb(200, 100, 50) else argb(100, 150, 200) }
        val decoder = QueueDecoder(listOf(EnhanceEngine.ImageBuffer(8, 8, noisyPixels)))
        val encoder = RecordingEncoder()
        val restormer = TrackingRestormer()
        val engine = EnhanceEngine(
            decoder = decoder,
            encoder = encoder,
            zeroDce = null,
            restormer = restormer,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val input = File.createTempFile("input", ".jpg")
        val output = File.createTempFile("output", ".jpg")

        val result = engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
                tileSize = 4,
                overlap = 2,
            ),
        )

        val pipeline = result.pipeline
        assertTrue(pipeline.tileUsed, "тайлинг должен быть использован")
        assertTrue(pipeline.hasSeamFix, "seam fix должен быть применен при overlap > 0")
        assertTrue(pipeline.seamMaxDelta >= 0f, "seamMaxDelta должен быть неотрицательным")
        assertTrue(pipeline.seamMeanDelta >= 0f, "seamMeanDelta должен быть неотрицательным")
        assertTrue(pipeline.seamArea >= 0, "seamArea должен быть неотрицательным")

        input.delete()
        output.delete()
    }

    @Test
    fun `timings capture all pipeline stages`() = runTest {
        val pixels = IntArray(16) { argb(50, 50, 50) }
        val decoder = QueueDecoder(listOf(EnhanceEngine.ImageBuffer(4, 4, pixels)))
        val encoder = RecordingEncoder()
        val zeroDce = TrackingZeroDce()
        val restormer = TrackingRestormer()
        val engine = EnhanceEngine(
            decoder = decoder,
            encoder = encoder,
            zeroDce = zeroDce,
            restormer = restormer,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val input = File.createTempFile("input", ".jpg")
        val output = File.createTempFile("output", ".jpg")

        val result = engine.enhance(
            EnhanceEngine.Request(
                source = input,
                strength = 1f,
                outputFile = output,
                tileSize = 2,
                overlap = 1,
            ),
        )

        val timings = result.timings
        assertTrue(timings.decode >= 0, "decode time должен быть неотрицательным")
        assertTrue(timings.metrics >= 0, "metrics time должен быть неотрицательным")
        assertTrue(timings.zeroDce >= 0, "zeroDce time должен быть неотрицательным")
        assertTrue(timings.restormer >= 0, "restormer time должен быть неотрицательным")
        assertTrue(timings.sharpen >= 0, "sharpen time должен быть неотрицательным")
        assertTrue(timings.vibrance >= 0, "vibrance time должен быть неотрицательным")
        assertTrue(timings.encode >= 0, "encode time должен быть неотрицательным")
        assertTrue(timings.total >= 0, "total time должен быть неотрицательным")
        assertTrue(timings.elapsed >= 0, "elapsed time должен быть неотрицательным")

        val sum = timings.decode + timings.metrics + timings.zeroDce + 
                  timings.restormer + timings.blend + timings.sharpen + 
                  timings.vibrance + timings.encode + timings.exif
        assertTrue(sum <= timings.total + 10, "сумма отдельных этапов не должна сильно превышать total")

        input.delete()
        output.delete()
    }

    private fun argb(r: Int, g: Int, b: Int): Int {
        val rr = (r and 0xFF)
        val gg = (g and 0xFF)
        val bb = (b and 0xFF)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    private fun assertClose(expected: Float, actual: Float, epsilon: Float) {
        assertTrue(
            abs(expected - actual) <= epsilon,
            "expected=$expected actual=$actual",
        )
    }

    private fun assertNonDecreasing(values: List<Float>, label: String) {
        values.zipWithNext().forEachIndexed { index, (previous, next) ->
            assertTrue(
                "$label должен не уменьшаться между t=${index * 25} и t=${(index + 1) * 25}",
                next + 1e-4f >= previous,
            )
        }
    }

    private class QueueDecoder(private val buffers: MutableList<EnhanceEngine.ImageBuffer>) : EnhanceEngine.ImageDecoder {
        constructor(buffers: List<EnhanceEngine.ImageBuffer>) : this(buffers.toMutableList())
        override fun decode(file: File): EnhanceEngine.ImageBuffer {
            if (buffers.isEmpty()) error("no buffers left")
            return buffers.removeAt(0).copy()
        }
    }

    private class RecordingEncoder : EnhanceEngine.ImageEncoder {
        var lastBuffer: EnhanceEngine.ImageBuffer? = null
        override fun encode(buffer: EnhanceEngine.ImageBuffer, target: File) {
            lastBuffer = buffer.copy()
            target.writeBytes(ByteArray(0))
        }
    }

    private class TrackingZeroDce : EnhanceEngine.ZeroDceModel {
        data class Call(val delegate: EnhanceEngine.Delegate, val iterations: Int)
        val calls = mutableListOf<Call>()
        override val backend: EnhanceEngine.ModelBackend = EnhanceEngine.ModelBackend.TFLITE
        override val checksum: String = "zero-dce-test"
        override suspend fun enhance(
            buffer: EnhanceEngine.ImageBuffer,
            delegate: EnhanceEngine.Delegate,
            iterations: Int,
        ): EnhanceEngine.ModelResult {
            calls += Call(delegate, iterations)
            val pixels = IntArray(buffer.pixels.size) { argb(200, 200, 200) }
            return EnhanceEngine.ModelResult(
                buffer = EnhanceEngine.ImageBuffer(buffer.width, buffer.height, pixels),
                delegate = delegate,
            )
        }
    }

    private class FallbackZeroDce : EnhanceEngine.ZeroDceModel {
        override val backend: EnhanceEngine.ModelBackend = EnhanceEngine.ModelBackend.TFLITE
        override val checksum: String = "zero-dce-fallback"
        override suspend fun enhance(
            buffer: EnhanceEngine.ImageBuffer,
            delegate: EnhanceEngine.Delegate,
            iterations: Int,
        ): EnhanceEngine.ModelResult {
            val pixels = IntArray(buffer.pixels.size) { argb(180, 180, 180) }
            return EnhanceEngine.ModelResult(
                buffer = EnhanceEngine.ImageBuffer(buffer.width, buffer.height, pixels),
                delegate = EnhanceEngine.Delegate.CPU,
            )
        }
    }

    private class TrackingRestormer : EnhanceEngine.RestormerModel {
        var calls: Int = 0
        override val backend: EnhanceEngine.ModelBackend = EnhanceEngine.ModelBackend.TFLITE
        override val checksum: String = "restormer-test"
        override suspend fun denoise(
            tile: EnhanceEngine.ImageBuffer,
            delegate: EnhanceEngine.Delegate,
        ): EnhanceEngine.ModelResult {
            calls++
            val pixels = IntArray(tile.pixels.size) { argb(64, 64, 192) }
            return EnhanceEngine.ModelResult(
                buffer = EnhanceEngine.ImageBuffer(tile.width, tile.height, pixels),
                delegate = delegate,
            )
        }
    }

    private class FallbackRestormer : EnhanceEngine.RestormerModel {
        override val backend: EnhanceEngine.ModelBackend = EnhanceEngine.ModelBackend.TFLITE
        override val checksum: String = "restormer-fallback"
        override suspend fun denoise(
            tile: EnhanceEngine.ImageBuffer,
            delegate: EnhanceEngine.Delegate,
        ): EnhanceEngine.ModelResult {
            val pixels = IntArray(tile.pixels.size) { argb(32, 32, 160) }
            val resolvedDelegate = if (delegate == EnhanceEngine.Delegate.GPU) {
                EnhanceEngine.Delegate.CPU
            } else {
                delegate
            }
            return EnhanceEngine.ModelResult(
                buffer = EnhanceEngine.ImageBuffer(tile.width, tile.height, pixels),
                delegate = resolvedDelegate,
            )
        }
    }
}

private fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b
