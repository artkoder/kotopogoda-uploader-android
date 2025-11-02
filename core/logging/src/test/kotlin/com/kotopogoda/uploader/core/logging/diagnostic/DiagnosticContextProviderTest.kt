package com.kotopogoda.uploader.core.logging.diagnostic

import com.kotopogoda.uploader.core.settings.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticContextProviderTest {

    private val appInfoProvider = object : AppInfoProvider {
        override fun appInfo(): AppInfo = AppInfo(
            applicationId = "com.example.app",
            versionName = "1.2.3",
            contractVersion = "v1",
            buildType = "debug",
        )
    }

    private val deviceInfoProvider = object : DeviceInfoProvider {
        override fun deviceInfo(): DeviceInfo = DeviceInfo(
            manufacturer = "ACME",
            model = "MegaPhone",
            brand = "ACME",
            device = "megaphone",
            androidRelease = "14",
            sdkInt = 34,
        )
    }

    @Test
    fun `snapshot includes base metadata`() {
        val provider = DiagnosticContextProvider(appInfoProvider, deviceInfoProvider)
        val metadata = provider.snapshot()

        assertEquals("1.2.3", metadata["app_version"])
        assertEquals("v1", metadata["api_contract_version"])
        assertEquals("ACME", metadata["device_manufacturer"])
        assertEquals("MegaPhone", metadata["device_model"])
        assertTrue(metadata.containsKey("session_id"))
        assertTrue(metadata["session_id"]!!.isNotBlank())
    }

    @Test
    fun `updateSettings overrides dynamic metadata`() {
        val provider = DiagnosticContextProvider(appInfoProvider, deviceInfoProvider)
        provider.updateSettings(
            AppSettings(
                baseUrl = "https://example.com",
                appLogging = false,
                httpLogging = true,
                persistentQueueNotification = false,
                previewQuality = com.kotopogoda.uploader.core.settings.PreviewQuality.BALANCED,
            )
        )

        val metadata = provider.snapshot()

        assertEquals("https://example.com", metadata["base_url"])
        assertEquals("false", metadata["app_logging"])
        assertEquals("true", metadata["http_logging"])
        assertEquals("false", metadata["persistent_queue_notification"])
    }

    @Test
    fun `updateExtra merges arbitrary metadata`() {
        val provider = DiagnosticContextProvider(appInfoProvider, deviceInfoProvider)
        provider.updateExtra(mapOf("device_id" to "dev-123"))

        val metadata = provider.snapshot()

        assertEquals("dev-123", metadata["device_id"])
    }
}
