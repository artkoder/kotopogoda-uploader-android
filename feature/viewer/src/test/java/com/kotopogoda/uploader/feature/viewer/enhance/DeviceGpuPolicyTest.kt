package com.kotopogoda.uploader.feature.viewer.enhance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceGpuPolicyTest {

    @Test
    fun `exynos hardware forces cpu mode`() {
        val fingerprint = DeviceGpuPolicy.DeviceFingerprint(
            hardware = "Exynos2100",
            board = "universal2100",
            manufacturer = "Samsung",
            model = "SM-A528B",
        )

        assertTrue(DeviceGpuPolicy.isExynosSmG99xFingerprint(fingerprint))
        assertEquals("device_blacklist", DeviceGpuPolicy.resolveForceCpuReason(fingerprint))
    }

    @Test
    fun `exynos board forces cpu mode`() {
        val fingerprint = DeviceGpuPolicy.DeviceFingerprint(
            hardware = "universal",
            board = "exynos-sample-board",
            manufacturer = "Other",
            model = "SM-S901B",
        )

        assertTrue(DeviceGpuPolicy.isExynosSmG99xFingerprint(fingerprint))
        assertEquals("device_blacklist", DeviceGpuPolicy.resolveForceCpuReason(fingerprint))
    }

    @Test
    fun `sm g99 family forces cpu mode`() {
        val fingerprint = DeviceGpuPolicy.DeviceFingerprint(
            hardware = "snapdragon",
            board = "qcom",
            manufacturer = "Samsung",
            model = "sm-g990b",
        )

        assertTrue(DeviceGpuPolicy.isExynosSmG99xFingerprint(fingerprint))
        assertEquals("device_blacklist", DeviceGpuPolicy.resolveForceCpuReason(fingerprint))
    }

    @Test
    fun `non exynos non sm g99 devices keep gpu`() {
        val fingerprint = DeviceGpuPolicy.DeviceFingerprint(
            hardware = "tensor",
            board = "gs101",
            manufacturer = "Google",
            model = "Pixel 7",
        )

        assertFalse(DeviceGpuPolicy.isExynosSmG99xFingerprint(fingerprint))
        assertEquals(null, DeviceGpuPolicy.resolveForceCpuReason(fingerprint))
    }
}
