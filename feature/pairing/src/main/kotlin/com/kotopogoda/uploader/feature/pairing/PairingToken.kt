package com.kotopogoda.uploader.feature.pairing

import java.util.Locale

private val pairingTokenRegex = Regex("^[A-Z2-9]{6,8}$")

internal const val PAIRING_TOKEN_FORMAT_ERROR = "Токен должен состоять из 6–8 символов A–Z и цифр 2–9"

internal fun normalizePairingToken(raw: String?): String? {
    val normalized = raw?.trim()?.uppercase(Locale.ROOT)
    if (normalized.isNullOrEmpty()) {
        return null
    }
    return normalized.takeIf { pairingTokenRegex.matches(it) }
}
