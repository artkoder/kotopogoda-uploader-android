package com.kotopogoda.uploader.core.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    companion object {
        private const val DATA_STORE_FILE_NAME = "app_settings.preferences_pb"

        @Provides
        @Singleton
        @SettingsPreferencesStore
        fun provideSettingsDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> {
            return PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                migrations = listOf(appLoggingBootstrapMigration()),
            ) {
                context.preferencesDataStoreFile(DATA_STORE_FILE_NAME)
            }
        }

        private fun appLoggingBootstrapMigration(): DataMigration<Preferences> {
            return object : DataMigration<Preferences> {
                override suspend fun shouldMigrate(currentData: Preferences): Boolean {
                    return currentData[APP_LOGGING_BOOTSTRAPPED_KEY] != true
                }

                override suspend fun migrate(currentData: Preferences): Preferences {
                    val mutable = currentData.mutableCopy()
                    mutable[APP_LOGGING_KEY] = true
                    mutable[APP_LOGGING_BOOTSTRAPPED_KEY] = true
                    return mutable
                }

                override suspend fun cleanUp() = Unit
            }
        }

        private val APP_LOGGING_KEY = booleanPreferencesKey("app_logging")
        private val APP_LOGGING_BOOTSTRAPPED_KEY = booleanPreferencesKey("app_logging_bootstrapped")

        private fun Preferences.mutableCopy(): MutablePreferences {
            val mutable = mutablePreferencesOf()
            for ((key, value) in asMap()) {
                @Suppress("UNCHECKED_CAST")
                mutable[key as Preferences.Key<Any>] = value
            }
            return mutable
        }

    }
}
