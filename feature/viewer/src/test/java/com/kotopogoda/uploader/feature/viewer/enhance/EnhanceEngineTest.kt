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
        assertTrue("sharpness must be positive", metrics.bSharpness > 0.1)
        assertTrue("noise must be positive", metrics.nNoise > 0.05)
    }

    @Test
    fun `profile reacts to strength`() {
        val metrics = EnhanceEngine.Metrics(
            lMean = 0.3,
            pDark = 0.4,
            bSharpness = 0.2,
            nNoise = 0.5,
        )
        val zero = EnhanceEngine.ProfileCalculator.calculate(metrics, 0f)
        assertTrue(zero.isLowLight)
        assertEquals(0f, zero.kDce, 1e-6f)
        assertEquals(0f, zero.restormerMix, 1e-6f)
        assertEquals(0f, zero.alphaDetail, 1e-6f)
        assertEquals(0f, zero.sharpenAmount, 1e-6f)
        assertClose(2.95f, zero.sharpenRadius, 1e-3f)
        assertClose(0.018f, zero.sharpenThreshold, 1e-3f)
        assertEquals(0f, zero.vibranceGain, 1e-6f)
        assertClose(0.91f, zero.saturationGain, 1e-3f)
        val full = EnhanceEngine.ProfileCalculator.calculate(metrics, 1f)
        assertTrue(full.isLowLight)
        assertClose(0.743f, full.kDce, 1e-3f)
        assertClose(0.5f, full.restormerMix, 1e-3f)
        assertClose(0.25f, full.alphaDetail, 1e-3f)
        assertClose(0.54f, full.sharpenAmount, 1e-3f)
        assertClose(2.95f, full.sharpenRadius, 1e-3f)
        assertClose(0.018f, full.sharpenThreshold, 1e-3f)
        assertClose(0.371f, full.vibranceGain, 1e-3f)
        assertClose(1.43f, full.saturationGain, 1e-3f)
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
        override suspend fun enhance(
            buffer: EnhanceEngine.ImageBuffer,
            delegate: EnhanceEngine.Delegate,
            iterations: Int,
        ): EnhanceEngine.ImageBuffer {
            calls += Call(delegate, iterations)
            val pixels = IntArray(buffer.pixels.size) { argb(200, 200, 200) }
            return EnhanceEngine.ImageBuffer(buffer.width, buffer.height, pixels)
        }
    }

    private class TrackingRestormer : EnhanceEngine.RestormerModel {
        var calls: Int = 0
        override suspend fun denoise(
            tile: EnhanceEngine.ImageBuffer,
            delegate: EnhanceEngine.Delegate,
        ): EnhanceEngine.ImageBuffer {
            calls++
            val pixels = IntArray(tile.pixels.size) { argb(64, 64, 192) }
            return EnhanceEngine.ImageBuffer(tile.width, tile.height, pixels)
        }
    }
}

private fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b
