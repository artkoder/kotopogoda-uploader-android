package com.kotopogoda.uploader.core.logging.diagnostic

import com.kotopogoda.uploader.core.settings.AppSettings
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiagnosticContextProvider @Inject constructor(
    private val appInfoProvider: AppInfoProvider,
    private val deviceInfoProvider: DeviceInfoProvider,
) {

    private val sessionId: String = UUID.randomUUID().toString()
    private val settingsMetadata = AtomicReference<Map<String, String>>(emptyMap())
    private val extraMetadata = AtomicReference<Map<String, String>>(emptyMap())

    private val baseMetadata: Map<String, String> by lazy {
        val appInfo = appInfoProvider.appInfo()
        val deviceInfo = deviceInfoProvider.deviceInfo()
        buildMap {
            put("session_id", sessionId)
            put("app_version", appInfo.versionName)
            put("api_contract_version", appInfo.contractVersion)
            put("app_id", appInfo.applicationId)
            put("app_build_type", appInfo.buildType)
            put("device_manufacturer", deviceInfo.manufacturer)
            put("device_model", deviceInfo.model)
            put("device_brand", deviceInfo.brand)
            put("device_code", deviceInfo.device)
            put("android_release", deviceInfo.androidRelease)
            put("android_sdk", deviceInfo.sdkInt.toString())
        }.filterValues { it.isNotBlank() }
    }

    fun updateSettings(settings: AppSettings) {
        settingsMetadata.set(
            mapOf(
                "base_url" to settings.baseUrl,
                "app_logging" to settings.appLogging.toString(),
                "http_logging" to settings.httpLogging.toString(),
                "persistent_queue_notification" to settings.persistentQueueNotification.toString(),
            ).filterValues { it.isNotBlank() }
        )
    }

    fun updateExtra(metadata: Map<String, String>) {
        extraMetadata.set(metadata.filterValues { it.isNotBlank() })
    }

    fun snapshot(): Map<String, String> {
        return baseMetadata + settingsMetadata.get() + extraMetadata.get()
    }
}
