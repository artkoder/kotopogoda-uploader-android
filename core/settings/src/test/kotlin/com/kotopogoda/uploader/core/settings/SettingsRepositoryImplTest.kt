package com.kotopogoda.uploader.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.kotopogoda.uploader.core.logging.test.MainDispatcherRule
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryImplTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun appLogging_defaultTrue_whenNotPersisted() = runTest {
        val permissionProvider = FakeNotificationPermissionProvider(initial = true)
        val dataStore = createDataStore(backgroundScope)
        val repository = createRepository(dataStore, permissionProvider, mainDispatcherRule.dispatcher)

        val settings = repository.flow.first()

        assertTrue(settings.appLogging)
    }

    @Test
    fun appLogging_updatesWhenPreferenceChanges() = runTest {
        val permissionProvider = FakeNotificationPermissionProvider(initial = true)
        val dataStore = createDataStore(backgroundScope)
        val repository = createRepository(dataStore, permissionProvider, mainDispatcherRule.dispatcher)

        repository.setAppLogging(false)
        advanceUntilIdle()
        val disabled = repository.flow.first { !it.appLogging }
        assertFalse(disabled.appLogging)

        repository.setAppLogging(true)
        advanceUntilIdle()
        val enabled = repository.flow.first { it.appLogging }
        assertTrue(enabled.appLogging)
    }

    @Test
    fun httpLogging_defaultTrue_whenNotPersisted() = runTest {
        val permissionProvider = FakeNotificationPermissionProvider(initial = true)
        val dataStore = createDataStore(backgroundScope)
        val repository = createRepository(dataStore, permissionProvider, mainDispatcherRule.dispatcher)

        val settings = repository.flow.first()

        assertTrue(settings.httpLogging)
    }

    @Test
    fun persistentQueueNotification_defaultFalse_whenPermissionMissing() = runTest {
        val permissionProvider = FakeNotificationPermissionProvider(initial = false)
        val dataStore = createDataStore(backgroundScope)
        val repository = createRepository(dataStore, permissionProvider, mainDispatcherRule.dispatcher)

        val settings = repository.flow.first()

        assertFalse(settings.persistentQueueNotification)
    }

    @Test
    fun persistentQueueNotification_defaultsToTrue_whenPermissionGranted() = runTest {
        val permissionProvider = FakeNotificationPermissionProvider(initial = true)
        val dataStore = createDataStore(backgroundScope)
        val repository = createRepository(dataStore, permissionProvider, mainDispatcherRule.dispatcher)

        val settings = repository.flow.first()

        assertTrue(settings.persistentQueueNotification)
    }

    @Test
    fun persistentQueueNotification_updates_whenPermissionChanges() = runTest {
        val permissionProvider = FakeNotificationPermissionProvider(initial = false)
        val dataStore = createDataStore(backgroundScope)
        val repository = createRepository(dataStore, permissionProvider, mainDispatcherRule.dispatcher)

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
