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
        assertTrue("sharpness must stay high", metrics.bSharpness > 0.9)
        assertTrue("noise must be within calibrated range", metrics.nNoise in 0.5..0.9)
    }

    @Test
    fun `profile reacts to strength`() {
        val metrics = EnhanceEngine.Metrics(
            lMean = 0.3,
            pDark = 0.4,
            bSharpness = 0.2,
            nNoise = 0.5,
        )
        data class Expectation(
            val kDce: Float,
            val restormerMix: Float,
            val alphaDetail: Float,
            val sharpenAmount: Float,
            val sharpenRadius: Float,
            val sharpenThreshold: Float,
            val vibranceGain: Float,
            val saturationGain: Float,
        )

        val points = listOf(
            0f to Expectation(0.272222f, 0.2f, 0.09f, 0.2475f, 3.4f, 0.014f, 0.194196f, 0.89f),
            0.25f to Expectation(0.398611f, 0.246875f, 0.111094f, 0.294766f, 3.4f, 0.014f, 0.264997f, 0.974375f),
            0.5f to Expectation(0.525f, 0.35f, 0.1575f, 0.39875f, 3.4f, 0.014f, 0.420759f, 1.16f),
            0.75f to Expectation(0.651389f, 0.453125f, 0.203906f, 0.502734f, 3.4f, 0.014f, 0.576521f, 1.345625f),
            1f to Expectation(0.777778f, 0.5f, 0.225f, 0.55f, 3.4f, 0.014f, 0.647321f, 1.43f),
        )

        points.forEach { (strength, expected) ->
            val profile = EnhanceEngine.ProfileCalculator.calculate(metrics, strength)
            assertTrue("low light flag should stay true", profile.isLowLight)
            assertClose(expected.kDce, profile.kDce, 1e-3f)
            assertClose(expected.restormerMix, profile.restormerMix, 1e-3f)
            assertClose(expected.alphaDetail, profile.alphaDetail, 1e-3f)
            assertClose(expected.sharpenAmount, profile.sharpenAmount, 1e-3f)
            assertClose(expected.sharpenRadius, profile.sharpenRadius, 1e-3f)
            assertClose(expected.sharpenThreshold, profile.sharpenThreshold, 1e-3f)
            assertClose(expected.vibranceGain, profile.vibranceGain, 1e-3f)
            assertClose(expected.saturationGain, profile.saturationGain, 1e-3f)
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
}

private fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b
