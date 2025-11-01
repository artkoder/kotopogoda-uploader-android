package com.kotopogoda.uploader.core.settings

data class AppSettings(
    val baseUrl: String,
    val appLogging: Boolean,
    val httpLogging: Boolean,
    val persistentQueueNotification: Boolean,
    val previewQuality: PreviewQuality,
)

enum class PreviewQuality {
    BALANCED,
    QUALITY,
}
