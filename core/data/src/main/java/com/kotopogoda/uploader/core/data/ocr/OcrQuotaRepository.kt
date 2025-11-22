package com.kotopogoda.uploader.core.data.ocr

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.kotopogoda.uploader.core.settings.SettingsPreferencesStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class OcrQuotaRepository @Inject constructor(
    @SettingsPreferencesStore private val dataStore: DataStore<Preferences>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _percent = MutableStateFlow<Int?>(null)
    val percent: StateFlow<Int?> = _percent.asStateFlow()

    private val preferencesFlow: Flow<Int?> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { preferences ->
            preferences[OCR_REMAINING_PERCENT_KEY]
        }

    init {
        // Загружаем сохранённое значение при инициализации
        CoroutineScope(ioDispatcher).launch {
            preferencesFlow.collect { savedPercent ->
                _percent.value = savedPercent
            }
        }
    }

    suspend fun updatePercent(percent: Int) {
        val normalized = percent.coerceIn(0, 100)
        _percent.value = normalized
        withContext(ioDispatcher) {
            dataStore.edit { preferences ->
                preferences[OCR_REMAINING_PERCENT_KEY] = normalized
            }
        }
    }

    private companion object {
        private val OCR_REMAINING_PERCENT_KEY = intPreferencesKey("ocr_remaining_percent")
    }
}
