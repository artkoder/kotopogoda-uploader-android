package com.kotopogoda.uploader.feature.viewer.enhance

import android.os.Build
import java.util.Locale

/**
 * Определяет девайсные ограничения для работы GPU делегата.
 */
object DeviceGpuPolicy {

    data class DeviceFingerprint(
        val hardware: String,
        val board: String,
        val manufacturer: String,
        val model: String,
    )

    private val fingerprint = DeviceFingerprint(
        hardware = Build.HARDWARE.orEmpty(),
        board = Build.BOARD.orEmpty(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
    )

    internal fun isExynosSmG99xFingerprint(fingerprint: DeviceFingerprint): Boolean {
        val normalizedModel = fingerprint.model.uppercase(Locale.US)
        val normalizedHardware = fingerprint.hardware.lowercase(Locale.US)
        val normalizedBoard = fingerprint.board.lowercase(Locale.US)

        return normalizedHardware.contains("exynos") ||
            normalizedBoard.contains("exynos") ||
            normalizedModel.startsWith("SM-G99")
    }

    internal fun resolveForceCpuReason(fingerprint: DeviceFingerprint = this.fingerprint): String? {
        return if (isExynosSmG99xFingerprint(fingerprint)) {
            "device_blacklist"
        } else {
            null
        }
    }

    val isExynosSmG99x: Boolean = isExynosSmG99xFingerprint(fingerprint)

    val forceCpuReason: String? = resolveForceCpuReason(fingerprint)

    fun fingerprint(): DeviceFingerprint = fingerprint
}
