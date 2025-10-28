package com.kotopogoda.uploader.feature.viewer.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `metrics for white image`() {
        val buffer = EnhanceEngine.ImageBuffer(4, 4, IntArray(16) { argb(255, 255, 255) })
        val metrics = EnhanceEngine.MetricsCalculator.calculate(buffer)
        assertEquals(1.0, metrics.lMean, 1e-6)
        assertEquals(0.0, metrics.pDark, 1e-6)
        assertEquals(0.0, metrics.bSharpness, 1e-6)
        assertEquals(0.0, metrics.nNoise, 1e-6)
    }

    @Test
    fun `profile curves for 0-100 percent`() {
        val metrics = EnhanceEngine.Metrics(
            lMean = 0.4,
            pDark = 0.2,
            bSharpness = 0.3,
            nNoise = 0.5,
        )
        val expected = listOf(
            StrengthExpectation(
                strength = 0f,
                luminance = 1.0f,
                dark = 0.06f,
                contrast = 1.0f,
                restormer = 0.0f,
                sharpen = 1.0f,
                saturation = 1.0f,
            ),
            StrengthExpectation(
                strength = 0.25f,
                luminance = 1.0234375f,
                dark = 0.081875f,
                contrast = 1.025f,
                restormer = 0.078125f,
                sharpen = 1.0576172f,
                saturation = 1.0046875f,
            ),
            StrengthExpectation(
                strength = 0.5f,
                luminance = 1.075f,
                dark = 0.13f,
                contrast = 1.08f,
                restormer = 0.25f,
                sharpen = 1.15f,
                saturation = 1.015f,
            ),
            StrengthExpectation(
                strength = 0.75f,
                luminance = 1.1265625f,
                dark = 0.178125f,
                contrast = 1.135f,
                restormer = 0.421875f,
                sharpen = 1.1951172f,
                saturation = 1.0253125f,
            ),
            StrengthExpectation(
                strength = 1.0f,
                luminance = 1.15f,
                dark = 0.2f,
                contrast = 1.16f,
                restormer = 0.5f,
                sharpen = 1.2f,
                saturation = 1.03f,
            ),
        )

        expected.forEachIndexed { index, data ->
            val profile = EnhanceEngine.ProfileCalculator.calculate(metrics, data.strength)
            assertClose("luminance[$index]", data.luminance, profile.luminanceGain)
            assertClose("dark[$index]", data.dark, profile.darkBoost)
            assertClose("contrast[$index]", data.contrast, profile.contrastGain)
            assertClose("restormer[$index]", data.restormer, profile.restormerMix)
            assertClose("sharpen[$index]", data.sharpen, profile.sharpenGain)
            assertClose("saturation[$index]", data.saturation, profile.saturationGain)
        }
    }

    private fun assertClose(message: String, expected: Float, actual: Float, epsilon: Float = 1e-4f) {
        assertTrue("$message expected=$expected actual=$actual", kotlin.math.abs(expected - actual) <= epsilon)
    }

    private data class StrengthExpectation(
        val strength: Float,
        val luminance: Float,
        val dark: Float,
        val contrast: Float,
        val restormer: Float,
        val sharpen: Float,
        val saturation: Float,
    )

    private fun argb(r: Int, g: Int, b: Int): Int {
        val rr = (r and 0xFF)
        val gg = (g and 0xFF)
        val bb = (b and 0xFF)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}
