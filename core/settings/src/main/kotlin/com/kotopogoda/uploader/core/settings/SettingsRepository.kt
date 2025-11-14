package com.kotopogoda.uploader.core.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val flow: Flow<AppSettings>
    suspend fun setBaseUrl(url: String)
    suspend fun setAppLogging(enabled: Boolean)
    suspend fun setHttpLogging(enabled: Boolean)
    suspend fun setPersistentQueueNotification(enabled: Boolean)
    suspend fun setPreviewQuality(quality: PreviewQuality)
    suspend fun setAutoDeleteAfterUpload(enabled: Boolean)
}
