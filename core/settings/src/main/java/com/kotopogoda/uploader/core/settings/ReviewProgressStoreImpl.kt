package com.kotopogoda.uploader.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class ReviewProgressStoreImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ReviewProgressStore {

    override suspend fun savePosition(folderId: String, index: Int, anchorDate: Instant?) {
        dataStore.edit { preferences ->
            preferences[lastIndexKey(folderId)] = index
            val anchorValue = anchorDate?.toEpochMilli() ?: NO_ANCHOR
            preferences[lastAnchorKey(folderId)] = anchorValue
        }
    }

    override suspend fun loadPosition(folderId: String): ReviewPosition? {
        val preferences = dataStore.data.first()
        val index = preferences[lastIndexKey(folderId)] ?: return null
        val anchorMillis = preferences[lastAnchorKey(folderId)] ?: NO_ANCHOR
        val anchor = if (anchorMillis != NO_ANCHOR) {
            runCatching { Instant.ofEpochMilli(anchorMillis) }.getOrNull()
        } else {
            null
        }
        return ReviewPosition(index = index, anchorDate = anchor)
    }

    override suspend fun clear(folderId: String) {
        dataStore.edit { preferences ->
            preferences.remove(lastIndexKey(folderId))
            preferences.remove(lastAnchorKey(folderId))
        }
    }

    companion object {
        private const val NO_ANCHOR: Long = -1L

        private fun lastIndexKey(folderId: String) = intPreferencesKey("last_index.$folderId")
        private fun lastAnchorKey(folderId: String) = longPreferencesKey("last_anchor.$folderId")
    }
}
