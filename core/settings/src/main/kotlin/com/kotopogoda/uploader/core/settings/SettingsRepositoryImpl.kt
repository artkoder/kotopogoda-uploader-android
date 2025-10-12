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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @DefaultBaseUrl private val defaultBaseUrl: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SettingsRepository {

    override val flow: Flow<AppSettings> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences ->
            val storedBaseUrl = preferences[BASE_URL_KEY]?.takeIf { it.isNotBlank() }
            AppSettings(
                baseUrl = storedBaseUrl ?: defaultBaseUrl,
                appLogging = preferences[APP_LOGGING_KEY] ?: false,
                httpLogging = preferences[HTTP_LOGGING_KEY] ?: false,
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

    private companion object {
        private val BASE_URL_KEY = stringPreferencesKey("base_url")
        private val APP_LOGGING_KEY = booleanPreferencesKey("app_logging")
        private val HTTP_LOGGING_KEY = booleanPreferencesKey("http_logging")
    }
}
