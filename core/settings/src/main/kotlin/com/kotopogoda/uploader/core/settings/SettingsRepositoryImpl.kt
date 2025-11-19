package com.kotopogoda.uploader.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @SettingsPreferencesStore private val dataStore: DataStore<Preferences>,
    @DefaultBaseUrl private val defaultBaseUrl: String,
    private val notificationPermissionProvider: NotificationPermissionProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SettingsRepository {

    private val preferencesFlow = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }

    override val flow: Flow<AppSettings> = preferencesFlow
        .combine(notificationPermissionProvider.permissionFlow()) { preferences, permissionGranted ->
            val storedBaseUrl = preferences[BASE_URL_KEY]?.takeIf { it.isNotBlank() }
            val persistentPreference = preferences[PERSISTENT_QUEUE_NOTIFICATION_KEY]
            val persistentValue = (persistentPreference ?: permissionGranted) && permissionGranted
            val previewQualityString = preferences[PREVIEW_QUALITY_KEY] ?: PreviewQuality.BALANCED.name
            val previewQuality = try {
                PreviewQuality.valueOf(previewQualityString)
            } catch (e: IllegalArgumentException) {
                PreviewQuality.BALANCED
            }
            AppSettings(
                baseUrl = storedBaseUrl ?: defaultBaseUrl,
                appLogging = preferences[APP_LOGGING_KEY] ?: true,
                httpLogging = preferences[HTTP_LOGGING_KEY] ?: true,
                persistentQueueNotification = persistentValue,
                previewQuality = previewQuality,
                autoDeleteAfterUpload = preferences[AUTO_DELETE_AFTER_UPLOAD_KEY] ?: true,
                forceCpuForEnhancement = preferences[FORCE_CPU_FOR_ENHANCEMENT_KEY] ?: false,
            )
        }
        .distinctUntilChanged()

    override suspend fun setBaseUrl(url: String) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                val normalized = url.trim()
                if (normalized.isBlank() || normalized == defaultBaseUrl) {
                    preferences.remove(BASE_URL_KEY)
                } else {
                    preferences[BASE_URL_KEY] = normalized
                }
            }
        }
    }

    override suspend fun setAppLogging(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences[APP_LOGGING_KEY] = enabled
            }
        }
    }

    override suspend fun setHttpLogging(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences[HTTP_LOGGING_KEY] = enabled
            }
        }
    }

    override suspend fun setPersistentQueueNotification(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences[PERSISTENT_QUEUE_NOTIFICATION_KEY] = enabled
            }
        }
    }

    override suspend fun setPreviewQuality(quality: PreviewQuality) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences[PREVIEW_QUALITY_KEY] = quality.name
            }
        }
    }

    override suspend fun setAutoDeleteAfterUpload(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences[AUTO_DELETE_AFTER_UPLOAD_KEY] = enabled
            }
        }
    }

    override suspend fun setForceCpuForEnhancement(enabled: Boolean) {
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences[FORCE_CPU_FOR_ENHANCEMENT_KEY] = enabled
            }
        }
    }

    private companion object {
        private val BASE_URL_KEY = stringPreferencesKey("base_url")
        private val APP_LOGGING_KEY = booleanPreferencesKey("app_logging")
        private val HTTP_LOGGING_KEY = booleanPreferencesKey("http_logging")
        private val PERSISTENT_QUEUE_NOTIFICATION_KEY = booleanPreferencesKey("persistent_queue_notification")
        private val PREVIEW_QUALITY_KEY = stringPreferencesKey("preview_quality")
        private val AUTO_DELETE_AFTER_UPLOAD_KEY = booleanPreferencesKey("auto_delete_after_upload")
        private val FORCE_CPU_FOR_ENHANCEMENT_KEY = booleanPreferencesKey("force_cpu_for_enhancement")
    }
}
