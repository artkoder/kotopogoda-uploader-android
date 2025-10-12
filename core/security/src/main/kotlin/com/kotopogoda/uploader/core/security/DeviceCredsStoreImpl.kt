package com.kotopogoda.uploader.core.security

import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

@Singleton
class DeviceCredsStoreImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DeviceCredsStore {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override val credsFlow: Flow<DeviceCreds?> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_DEVICE_ID || key == KEY_HMAC_KEY) {
                trySend(readCreds())
            }
        }
        trySend(readCreds())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    override suspend fun save(deviceId: String, hmacKey: String) {
        withContext(ioDispatcher) {
            prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_HMAC_KEY, hmacKey)
                .apply()
        }
    }

    override suspend fun get(): DeviceCreds? = withContext(ioDispatcher) {
        readCreds()
    }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            prefs.edit().clear().apply()
        }
    }

    private fun readCreds(): DeviceCreds? {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)
        val hmacKey = prefs.getString(KEY_HMAC_KEY, null)
        return if (!deviceId.isNullOrBlank() && !hmacKey.isNullOrBlank()) {
            DeviceCreds(deviceId = deviceId, hmacKey = hmacKey)
        } else {
            null
        }
    }

    private companion object {
        const val PREFS_NAME = "device_creds"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_HMAC_KEY = "hmac_key"
    }
}
