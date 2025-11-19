package com.kotopogoda.uploader.core.logging.diagnostics

import com.kotopogoda.uploader.core.logging.diagnostics.EnvironmentDiagnosticsProvider.Companion.MEDIA_LIMIT
import com.kotopogoda.uploader.core.logging.diagnostics.EnvironmentDiagnosticsProvider.Companion.QUEUE_LIMIT
import com.kotopogoda.uploader.core.settings.NotificationPermissionProvider
import com.kotopogoda.uploader.core.settings.SettingsRepository
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.text.Charsets

@Singleton
class EnvironmentDiagnosticsProvider @Inject constructor(
    private val appInfoProvider: AppInfoProvider,
    private val settingsRepository: SettingsRepository,
    private val notificationPermissionProvider: NotificationPermissionProvider,
    private val networkStatusProvider: NetworkStatusProvider,
    private val uploadQueueSnapshotProvider: UploadQueueSnapshotProvider,
    private val workInfoProvider: WorkInfoProvider,
    private val mediaStoreSnapshotProvider: MediaStoreSnapshotProvider,
    private val folderSelectionProvider: FolderSelectionProvider,
    private val persistedUriPermissionsProvider: PersistedUriPermissionsProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiagnosticsProvider {

    override suspend fun writeDiagnostics(target: File) {
        withContext(ioDispatcher) {
            val appInfo = appInfoProvider.getAppInfo()
            val settingsResult = runCatching { settingsRepository.flow.first() }
            val settings = settingsResult.getOrNull()
            val notificationsResult = runCatching { notificationPermissionProvider.permissionFlow().first() }
            val notificationsGranted = notificationsResult.getOrDefault(false)
            val queueResult = runCatching { uploadQueueSnapshotProvider.getQueued(QUEUE_LIMIT) }
            val uploadQueue = queueResult.getOrDefault(emptyList())
            val queueStatsResult = runCatching { uploadQueueSnapshotProvider.getStats() }
            val queueStats = queueStatsResult.getOrNull()
            val workResults = WORK_TAGS.mapValues { (_, tag) ->
                runCatching { workInfoProvider.getWorkInfosByTag(tag) }
            }
            val mediaResult = runCatching { mediaStoreSnapshotProvider.getRecent(MEDIA_LIMIT) }
            val folderResult = runCatching { folderSelectionProvider.currentSelection() }
            val persistedPermissionsResult = runCatching { persistedUriPermissionsProvider.getPersistedPermissions() }
            val networkValidated = runCatching { networkStatusProvider.isNetworkValidated() }.getOrDefault(false)

            val root = JSONObject().apply {
                put("generatedAt", Instant.now().toString())
                put("app", JSONObject().apply {
                    put("packageName", appInfo.packageName)
                    put("versionName", appInfo.versionName)
                    put("versionCode", appInfo.versionCode)
                    put("contractVersion", appInfo.contractVersion)
                })
                put("settings", JSONObject().apply {
                    if (settings != null) {
                        put("baseUrl", settings.baseUrl)
                        put("appLogging", settings.appLogging)
                        put("httpLogging", settings.httpLogging)
                        put("persistentQueueNotification", settings.persistentQueueNotification)
                        put("forceCpuForEnhancement", settings.forceCpuForEnhancement)
                    } else {
                        put("error", settingsResult.exceptionOrNull().toDiagnosticString())
                    }
                })
                put("permissions", JSONObject().apply {
                    put("notificationsGranted", notificationsGranted)
                    notificationsResult.exceptionOrNull()?.let { error ->
                        put("notificationsError", error.toDiagnosticString())
                    }
                    put("persistedUris", JSONArray().apply {
                        persistedPermissionsResult.getOrDefault(emptyList()).forEach { permission ->
                            put(
                                JSONObject().apply {
                                    put("uri", permission.uri)
                                    put("read", permission.read)
                                    put("write", permission.write)
                                    put("persistedTime", permission.persistedTime)
                                }
                            )
                        }
                    })
                    persistedPermissionsResult.exceptionOrNull()?.let { error ->
                        put("persistedUrisError", error.toDiagnosticString())
                    }
                })
                put("network", JSONObject().apply {
                    put("validated", networkValidated)
                })
                put("queue", JSONObject().apply {
                    put("count", uploadQueue.size)
                    queueStats?.let { stats ->
                        put("waiting", stats.waiting)
                        put("running", stats.running)
                        put("succeeded", stats.succeeded)
                        put("failed", stats.failed)
                    }
                    put("items", JSONArray().apply {
                        uploadQueue.forEach { item ->
                            put(
                                JSONObject().apply {
                                    put("id", item.id)
                                    putNullable("uri", item.uri)
                                    putNullable("displayName", item.displayName)
                                    putNullable("idempotencyKey", item.idempotencyKey)
                                    putNullable("size", item.size)
                                    put("state", item.state)
                                    put("createdAt", item.createdAt)
                                    putNullable("updatedAt", item.updatedAt)
                                    put("ageMillis", item.ageMillis)
                                    put("timeSinceUpdateMillis", item.timeSinceUpdateMillis)
                                    putNullable("lastErrorKind", item.lastErrorKind)
                                    putNullable("lastErrorHttpCode", item.lastErrorHttpCode)
                                }
                            )
                        }
                    })
                    queueResult.exceptionOrNull()?.let { error ->
                        put("error", error.toDiagnosticString())
                    }
                    queueStatsResult.exceptionOrNull()?.let { error ->
                        put("statsError", error.toDiagnosticString())
                    }
                })
                put("workManager", JSONObject().apply {
                    for ((name, result) in workResults) {
                        put(name, result.toJsonArray())
                    }
                })
                put("mediaStore", JSONArray().apply {
                    mediaResult.getOrDefault(emptyList()).forEach { item ->
                        put(
                            JSONObject().apply {
                                put("uri", item.uri)
                                putNullable("displayName", item.displayName)
                                putNullable("size", item.size)
                                putNullable("mimeType", item.mimeType)
                                putNullable("dateAddedMillis", item.dateAddedMillis)
                                putNullable("dateModifiedMillis", item.dateModifiedMillis)
                                putNullable("dateTakenMillis", item.dateTakenMillis)
                                putNullable("relativePath", item.relativePath)
                            }
                        )
                    }
                    mediaResult.exceptionOrNull()?.let { error ->
                        put(JSONObject().apply { put("error", error.toDiagnosticString()) })
                    }
                })
                val calendar = folderResult.getOrNull()?.let { folder ->
                    JSONObject().apply {
                        put("treeUri", folder.treeUri)
                        put("flags", folder.flags)
                        putNullable("lastScanAt", folder.lastScanAt)
                        putNullable("lastViewedPhotoId", folder.lastViewedPhotoId)
                        putNullable("lastViewedAt", folder.lastViewedAt)
                    }
                } ?: folderResult.exceptionOrNull()?.let { error ->
                    JSONObject().apply { put("error", error.toDiagnosticString()) }
                }
                if (calendar != null) {
                    put("calendar", calendar)
                } else {
                    put("calendar", JSONObject.NULL)
                }
            }

            target.writeText(root.toString(2), Charsets.UTF_8)
        }
    }

    private fun Throwable?.toDiagnosticString(): String {
        if (this == null) return ""
        val name = this::class.java.simpleName.ifBlank { this::class.java.name }
        val message = message?.takeIf { it.isNotBlank() }
        return buildString {
            append(name)
            if (message != null) {
                append(": ")
                append(message)
            }
        }
    }

    private fun Result<List<WorkInfoSnapshot>>.toJsonArray(): JSONArray {
        val array = JSONArray()
        getOrDefault(emptyList()).forEach { info ->
            array.put(
                JSONObject().apply {
                    put("id", info.id)
                    put("state", info.state)
                    put("runAttemptCount", info.runAttemptCount)
                    put("tags", info.tags.toJsonArray())
                    put("progress", info.progress.toJsonObject())
                    put("output", info.output.toJsonObject())
                }
            )
        }
        exceptionOrNull()?.let { error ->
            array.put(JSONObject().apply { put("error", error.toDiagnosticString()) })
        }
        return array
    }

    private fun Collection<String>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { value -> array.put(value) }
        return array
    }

    private fun Map<String, String>.toJsonObject(): JSONObject {
        val json = JSONObject()
        forEach { (key, value) -> json.put(key, value) }
        return json
    }

    private fun JSONObject.putNullable(name: String, value: String?) {
        if (value != null) {
            put(name, value)
        } else {
            put(name, JSONObject.NULL)
        }
    }

    private fun JSONObject.putNullable(name: String, value: Long?) {
        if (value != null) {
            put(name, value)
        } else {
            put(name, JSONObject.NULL)
        }
    }

    private fun JSONObject.putNullable(name: String, value: Int?) {
        if (value != null) {
            put(name, value)
        } else {
            put(name, JSONObject.NULL)
        }
    }

    companion object {
        internal const val QUEUE_LIMIT = 25
        internal const val MEDIA_LIMIT = 20
        private val WORK_TAGS = linkedMapOf(
            "upload" to "upload",
            "poll" to "poll",
        )
    }
}
