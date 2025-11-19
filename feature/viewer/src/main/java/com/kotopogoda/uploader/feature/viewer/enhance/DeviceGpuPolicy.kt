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
        hardware = Build.HARDWARE.ifEmpty { "" },
        board = Build.BOARD.ifEmpty { "" },
        manufacturer = Build.MANUFACTURER.ifEmpty { "" },
        model = Build.MODEL.ifEmpty { "" },
    )

    private val normalizedModel = fingerprint.model.uppercase(Locale.US)
    private val normalizedManufacturer = fingerprint.manufacturer.lowercase(Locale.US)
    private val normalizedHardware = fingerprint.hardware.lowercase(Locale.US)
    private val normalizedBoard = fingerprint.board.lowercase(Locale.US)

    private val samsungExynosBoard = normalizedHardware.contains("exynos") ||
        normalizedBoard.contains("exynos") ||
        normalizedHardware.contains("s5e") ||
        normalizedBoard.contains("s5e")

    val isExynosSmG99x: Boolean = normalizedManufacturer == "samsung" &&
        normalizedModel.startsWith("SM-G99") &&
        samsungExynosBoard

    val forceCpuReason: String? = if (isExynosSmG99x) {
        "device_blacklist"
    } else {
        null
    }

    fun fingerprint(): DeviceFingerprint = fingerprint
}
