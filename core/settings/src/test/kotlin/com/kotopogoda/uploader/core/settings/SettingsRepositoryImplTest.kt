package com.kotopogoda.uploader.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryImplTest {

    @Test
    fun persistentQueueNotification_defaultFalse_whenPermissionMissing() = runTest {
        val permissionProvider = FakeNotificationPermissionProvider(initial = false)
        val dataStore = createDataStore(backgroundScope)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = createRepository(dataStore, permissionProvider, dispatcher)

        val settings = repository.flow.first()

        assertFalse(settings.persistentQueueNotification)
    }

    @Test
    fun persistentQueueNotification_defaultsToTrue_whenPermissionGranted() = runTest {
        val permissionProvider = FakeNotificationPermissionProvider(initial = true)
        val dataStore = createDataStore(backgroundScope)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = createRepository(dataStore, permissionProvider, dispatcher)

        val settings = repository.flow.first()

        assertTrue(settings.persistentQueueNotification)
    }

    @Test
    fun persistentQueueNotification_updates_whenPermissionChanges() = runTest {
        val permissionProvider = FakeNotificationPermissionProvider(initial = false)
        val dataStore = createDataStore(backgroundScope)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = createRepository(dataStore, permissionProvider, dispatcher)

        val initial = repository.flow.first()
        assertFalse(initial.persistentQueueNotification)

        permissionProvider.set(true)
        advanceUntilIdle()
        val updated = repository.flow.first { it.persistentQueueNotification }

        assertTrue(updated.persistentQueueNotification)
    }

    private fun createRepository(
        dataStore: DataStore<Preferences>,
        permissionProvider: NotificationPermissionProvider,
        dispatcher: CoroutineDispatcher,
    ): SettingsRepositoryImpl {
        return SettingsRepositoryImpl(
            dataStore = dataStore,
            defaultBaseUrl = "https://example.com/",
            notificationPermissionProvider = permissionProvider,
            ioDispatcher = dispatcher,
        )
    }

    private fun createDataStore(scope: CoroutineScope): DataStore<Preferences> {
        val tempDir = createTempDirectory().toFile()
        tempDir.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(tempDir, "settings.preferences_pb") },
        )
    }

    private class FakeNotificationPermissionProvider(initial: Boolean) : NotificationPermissionProvider {
        private val state = MutableStateFlow(initial)

        override fun permissionFlow(): Flow<Boolean> = state

        fun set(value: Boolean) {
            state.value = value
        }
    }
}
