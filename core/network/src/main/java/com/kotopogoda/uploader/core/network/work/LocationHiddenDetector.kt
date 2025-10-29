package com.kotopogoda.uploader.core.network.work

internal object LocationHiddenDetector {
    fun isLocationHidden(vararg messages: String?): Boolean {
        return messages.any { raw ->
            if (raw.isNullOrBlank()) {
                false
            } else {
                val normalized = raw.trim().lowercase()
                normalized.contains("location_hidden_by_system") ||
                    (normalized.contains("location") &&
                        normalized.contains("hidden") &&
                        normalized.contains("system")) ||
                    normalized.contains("access_media_location")
            }
        }
    }
}
