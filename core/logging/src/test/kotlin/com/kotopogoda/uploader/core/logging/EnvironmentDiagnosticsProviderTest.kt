package com.kotopogoda.uploader.core.logging

import com.kotopogoda.uploader.core.logging.diagnostics.AppInfo
import com.kotopogoda.uploader.core.logging.diagnostics.AppInfoProvider
import com.kotopogoda.uploader.core.logging.diagnostics.EnvironmentDiagnosticsProvider
import com.kotopogoda.uploader.core.logging.diagnostics.FolderSelectionProvider
import com.kotopogoda.uploader.core.logging.diagnostics.FolderSnapshot
import com.kotopogoda.uploader.core.logging.diagnostics.MediaStoreItemSnapshot
import com.kotopogoda.uploader.core.logging.diagnostics.MediaStoreSnapshotProvider
import com.kotopogoda.uploader.core.logging.diagnostics.NetworkStatusProvider
import com.kotopogoda.uploader.core.logging.diagnostics.PersistedUriPermissionSnapshot
import com.kotopogoda.uploader.core.logging.diagnostics.PersistedUriPermissionsProvider
import com.kotopogoda.uploader.core.logging.diagnostics.QueueItemSnapshot
import com.kotopogoda.uploader.core.logging.diagnostics.QueueStatsSnapshot
import com.kotopogoda.uploader.core.logging.diagnostics.UploadQueueSnapshotProvider
import com.kotopogoda.uploader.core.logging.diagnostics.WorkInfoProvider
import com.kotopogoda.uploader.core.logging.diagnostics.WorkInfoSnapshot
import com.kotopogoda.uploader.core.settings.AppSettings
import com.kotopogoda.uploader.core.settings.NotificationPermissionProvider
import com.kotopogoda.uploader.core.settings.PreviewQuality
import com.kotopogoda.uploader.core.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject
 
class EnvironmentDiagnosticsProviderTest {

    private lateinit var tempFile: File

    @BeforeTest
    fun setup() {
        tempFile = File.createTempFile("diagnostics-test", ".json")
    }

    @AfterTest
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `writes diagnostics with mandatory sections`() = runBlocking {
        val provider = EnvironmentDiagnosticsProvider(
            appInfoProvider = FakeAppInfoProvider(),
            settingsRepository = FakeSettingsRepository(),
            notificationPermissionProvider = FakeNotificationPermissionProvider(),
            networkStatusProvider = FakeNetworkStatusProvider(validated = true),
            uploadQueueSnapshotProvider = FakeUploadQueueSnapshotProvider(),
            workInfoProvider = FakeWorkInfoProvider(),
            mediaStoreSnapshotProvider = FakeMediaStoreSnapshotProvider(),
            folderSelectionProvider = FakeFolderSelectionProvider(),
            persistedUriPermissionsProvider = FakePersistedPermissionsProvider(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        provider.writeDiagnostics(tempFile)

        val json = JSONObject(tempFile.readText())
        val app = json.getJSONObject("app")
        assertEquals("com.test.app", app.getString("packageName"))
        assertEquals("9.9.9", app.getString("versionName"))
        assertEquals("v-contract", app.getString("contractVersion"))

        val settings = json.getJSONObject("settings")
        assertEquals("https://api.test", settings.getString("baseUrl"))
        assertTrue(settings.getBoolean("appLogging"))

        val permissions = json.getJSONObject("permissions")
        assertTrue(permissions.getBoolean("notificationsGranted"))
        assertEquals(1, permissions.getJSONArray("persistedUris").length())

        val network = json.getJSONObject("network")
        assertTrue(network.getBoolean("validated"))

        val queue = json.getJSONObject("queue")
        assertEquals(2, queue.getInt("count"))
        assertEquals(2, queue.getInt("waiting"))
        assertEquals(1, queue.getInt("running"))
        assertEquals(3, queue.getInt("succeeded"))
        assertEquals(4, queue.getInt("failed"))
        assertEquals(2, queue.getJSONArray("items").length())
        val firstQueueItem = queue.getJSONArray("items").getJSONObject(0)
        assertEquals("queued", firstQueueItem.getString("state"))
        assertEquals(1000L, firstQueueItem.getLong("createdAt"))
        assertEquals(2000L, firstQueueItem.getLong("updatedAt"))
        assertEquals(300L, firstQueueItem.getLong("ageMillis"))
        assertEquals(100L, firstQueueItem.getLong("timeSinceUpdateMillis"))
        assertEquals("network", firstQueueItem.getString("lastErrorKind"))
        assertEquals(500, firstQueueItem.getInt("lastErrorHttpCode"))

        val workManager = json.getJSONObject("workManager")
        assertEquals(1, workManager.getJSONArray("upload").length())
        assertEquals(1, workManager.getJSONArray("poll").length())

        val media = json.getJSONArray("mediaStore")
        assertEquals(1, media.length())

        val calendar = json.getJSONObject("calendar")
        assertEquals("tree://uri", calendar.getString("treeUri"))
    }

    private class FakeAppInfoProvider : AppInfoProvider {
        override fun getAppInfo(): AppInfo = AppInfo(
            packageName = "com.test.app",
            versionName = "9.9.9",
            versionCode = 99L,
            contractVersion = "v-contract",
        )
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val flow: Flow<AppSettings> = flowOf(
            AppSettings(
                baseUrl = "https://api.test",
                appLogging = true,
                httpLogging = false,
                persistentQueueNotification = false,
                previewQuality = PreviewQuality.BALANCED,
                autoDeleteAfterUpload = true,
            )
        )

        override suspend fun setBaseUrl(url: String) = error("Not needed in tests")
        override suspend fun setAppLogging(enabled: Boolean) = error("Not needed in tests")
        override suspend fun setHttpLogging(enabled: Boolean) = error("Not needed in tests")
        override suspend fun setPersistentQueueNotification(enabled: Boolean) = error("Not needed in tests")
        override suspend fun setPreviewQuality(quality: PreviewQuality) = error("Not needed in tests")
        override suspend fun setAutoDeleteAfterUpload(enabled: Boolean) = error("Not needed in tests")
    }

    private class FakeNotificationPermissionProvider : NotificationPermissionProvider {
        override fun permissionFlow(): Flow<Boolean> = flowOf(true)
    }

    private class FakeNetworkStatusProvider(private val validated: Boolean) : NetworkStatusProvider {
        override fun isNetworkValidated(): Boolean = validated
    }

    private class FakeUploadQueueSnapshotProvider : UploadQueueSnapshotProvider {
        override suspend fun getQueued(limit: Int): List<QueueItemSnapshot> = listOf(
            QueueItemSnapshot(
                id = 1,
                uri = "uri://1",
                displayName = "one",
                idempotencyKey = "key1",
                size = 10L,
                state = "queued",
                createdAt = 1000L,
                updatedAt = 2000L,
                ageMillis = 300L,
                timeSinceUpdateMillis = 100L,
                lastErrorKind = "network",
                lastErrorHttpCode = 500,
            ),
            QueueItemSnapshot(
                id = 2,
                uri = "uri://2",
                displayName = "two",
                idempotencyKey = "key2",
                size = 20L,
                state = "queued",
                createdAt = 3000L,
                updatedAt = null,
                ageMillis = 400L,
                timeSinceUpdateMillis = 400L,
                lastErrorKind = null,
                lastErrorHttpCode = null,
            ),
        )

        override suspend fun getStats(): QueueStatsSnapshot = QueueStatsSnapshot(
            waiting = 2,
            running = 1,
            succeeded = 3,
            failed = 4,
        )
    }

    private class FakeWorkInfoProvider : WorkInfoProvider {
        override suspend fun getWorkInfosByTag(tag: String): List<WorkInfoSnapshot> = listOf(
            WorkInfoSnapshot(
                id = "123",
                state = "ENQUEUED",
                tags = setOf(tag, "extra"),
                runAttemptCount = 0,
                progress = mapOf("step" to "init"),
                output = emptyMap(),
            )
        )
    }

    private class FakeMediaStoreSnapshotProvider : MediaStoreSnapshotProvider {
        override suspend fun getRecent(limit: Int): List<MediaStoreItemSnapshot> = listOf(
            MediaStoreItemSnapshot(
                uri = "media://1",
                displayName = "photo.jpg",
                size = 1024,
                mimeType = "image/jpeg",
                dateAddedMillis = 1000,
                dateModifiedMillis = 2000,
                dateTakenMillis = 3000,
                relativePath = "Pictures/",
            )
        )
    }

    private class FakeFolderSelectionProvider : FolderSelectionProvider {
        override suspend fun currentSelection(): FolderSnapshot? = FolderSnapshot(
            treeUri = "tree://uri",
            flags = 3,
            lastScanAt = 1234L,
            lastViewedPhotoId = "abc",
            lastViewedAt = 5678L,
        )
    }

    private class FakePersistedPermissionsProvider : PersistedUriPermissionsProvider {
        override fun getPersistedPermissions(): List<PersistedUriPermissionSnapshot> = listOf(
            PersistedUriPermissionSnapshot(
                uri = "tree://uri",
                read = true,
                write = false,
                persistedTime = 42L,
            )
        )
    }
}
