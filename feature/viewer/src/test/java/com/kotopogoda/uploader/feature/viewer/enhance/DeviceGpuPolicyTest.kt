package com.kotopogoda.uploader.feature.viewer.enhance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceGpuPolicyTest {

    @Test
    fun `gpu delegate is always disabled`() {
        assertFalse(DeviceGpuPolicy.shouldUseGpu())
    }

    @Test
    fun `force cpu reason is constant`() {
        assertEquals("cpu_only", DeviceGpuPolicy.forceCpuReason)
    }

    @Test
    fun `exynos hardware is still flagged for telemetry`() {
        val fingerprint = DeviceGpuPolicy.DeviceFingerprint(
            hardware = "Exynos2100",
            board = "universal2100",
            manufacturer = "Samsung",
            model = "SM-A528B",
        )

        assertTrue(DeviceGpuPolicy.isExynosSmG99xFingerprint(fingerprint))
    }

    @Test
    fun `exynos board is still flagged for telemetry`() {
        val fingerprint = DeviceGpuPolicy.DeviceFingerprint(
            hardware = "universal",
            board = "exynos-sample-board",
            manufacturer = "Other",
            model = "SM-S901B",
        )

        assertTrue(DeviceGpuPolicy.isExynosSmG99xFingerprint(fingerprint))
    }

    @Test
    fun `sm g99 family is still flagged for telemetry`() {
        val fingerprint = DeviceGpuPolicy.DeviceFingerprint(
            hardware = "snapdragon",
            board = "qcom",
            manufacturer = "Samsung",
            model = "sm-g990b",
        )

        assertTrue(DeviceGpuPolicy.isExynosSmG99xFingerprint(fingerprint))
    }

    @Test
    fun `non exynos non sm g99 devices are not flagged`() {
        val fingerprint = DeviceGpuPolicy.DeviceFingerprint(
            hardware = "tensor",
            board = "gs101",
            manufacturer = "Google",
            model = "Pixel 7",
        )

        assertFalse(DeviceGpuPolicy.isExynosSmG99xFingerprint(fingerprint))
    }
}
