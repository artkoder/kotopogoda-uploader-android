package com.kotopogoda.uploader.core.data.upload

import com.kotopogoda.uploader.core.logging.diagnostic.AppInfo
import com.kotopogoda.uploader.core.logging.diagnostic.AppInfoProvider
import com.kotopogoda.uploader.core.logging.diagnostic.DeviceInfo
import com.kotopogoda.uploader.core.logging.diagnostic.DeviceInfoProvider
import com.kotopogoda.uploader.core.logging.diagnostic.DiagnosticContextProvider
import com.kotopogoda.uploader.core.settings.AppSettings
import org.junit.Before
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class UploadLogTest {

    @Before
    fun setUp() {
        val provider = DiagnosticContextProvider(
            appInfoProvider = object : AppInfoProvider {
                override fun appInfo(): AppInfo = AppInfo(
                    applicationId = "com.example.test",
                    versionName = "1.0.0",
                    contractVersion = "v1",
                    buildType = "debug",
                )
            },
            deviceInfoProvider = object : DeviceInfoProvider {
                override fun deviceInfo(): DeviceInfo = DeviceInfo(
                    manufacturer = "ACME",
                    model = "TestPhone",
                    brand = "ACME",
                    device = "testphone",
                    androidRelease = "14",
                    sdkInt = 34,
                )
            },
        )
        provider.updateSettings(
            AppSettings(
                baseUrl = "https://example.test",
                appLogging = true,
                httpLogging = false,
                persistentQueueNotification = true,
                previewQuality = com.kotopogoda.uploader.core.settings.PreviewQuality.BALANCED,
            )
        )
        provider.updateExtra(mapOf("device_id" to "device-123"))
        UploadLog.setDiagnosticContextProvider(provider)
    }

    @Test
    fun messageIncludesDiagnosticPrefixAndMasksSecrets() {
        val message = UploadLog.message(
            category = "APP/Test",
            action = "check",
            photoId = "photo-1",
            details = arrayOf(
                "queue_item_id" to 123L,
                "api_key" to "super-secret",
                "plain" to "value",
            ),
        )

        assertTrue(message.startsWith("APP/Test"))
        assertContains(message, "session_id=")
        assertContains(message, "app_version=1.0.0")
        assertContains(message, "api_contract_version=v1")
        assertContains(message, "queue_item_id=123")
        assertContains(message, "plain=value")
        assertContains(message, "action=check")
        assertContains(message, "photo_id=photo-1")
        assertContains(message, "api_key=***")
    }
}
