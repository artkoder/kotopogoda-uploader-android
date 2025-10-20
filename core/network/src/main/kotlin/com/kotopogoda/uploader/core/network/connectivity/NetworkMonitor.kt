package com.kotopogoda.uploader.core.network.connectivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.network.health.HealthMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectivityManager: ConnectivityManager,
    private val healthMonitor: HealthMonitor,
) {

    private val started = AtomicBoolean(false)
    private val networkValidated = AtomicBoolean(false)
    private val airplaneModeEnabled = AtomicBoolean(false)
    private val lastReported = AtomicReference<Boolean?>(null)

    private val _isNetworkValidated = MutableStateFlow(false)
    val isNetworkValidated: StateFlow<Boolean> = _isNetworkValidated.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkValidation(queryNetworkValidated(network) || queryActiveNetworkValidated())
            healthMonitor.refreshNow()
        }

        override fun onLost(network: Network) {
            updateNetworkValidation(queryActiveNetworkValidated())
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val validated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            updateNetworkValidation(validated || queryActiveNetworkValidated())
        }
    }

    private val airplaneModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_AIRPLANE_MODE_CHANGED) return
            val enabled = intent.getBooleanExtra("state", false)
            airplaneModeEnabled.set(enabled)
            emitCombinedState()
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        airplaneModeEnabled.set(isAirplaneModeOn(context))
        updateNetworkValidation(queryActiveNetworkValidated())
        registerAirplaneModeReceiver()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }
        connectivityManager.unregisterNetworkCallback(networkCallback)
        unregisterAirplaneModeReceiver()
    }

    private fun updateNetworkValidation(isValidated: Boolean) {
        networkValidated.set(isValidated)
        emitCombinedState()
    }

    private fun emitCombinedState() {
        val validated = networkValidated.get()
        val airplaneMode = airplaneModeEnabled.get()
        val combined = validated && !airplaneMode
        _isNetworkValidated.value = combined
        val previous = lastReported.getAndSet(combined)
        if (previous == null || previous != combined) {
            Timber.tag(TAG).i(
                UploadLog.message(
                    category = "NET/STATE",
                    action = if (combined) "online" else "offline",
                    details = arrayOf(
                        "validated" to validated,
                        "airplane_mode" to airplaneMode,
                    ),
                )
            )
        }
    }

    private fun queryActiveNetworkValidated(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return queryNetworkValidated(network)
    }

    @VisibleForTesting
    internal fun queryNetworkValidated(network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun registerAirplaneModeReceiver() {
        val filter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(airplaneModeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(airplaneModeReceiver, filter)
        }
    }

    private fun unregisterAirplaneModeReceiver() {
        runCatching { context.unregisterReceiver(airplaneModeReceiver) }
    }

    private fun isAirplaneModeOn(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }

    private companion object {
        private const val TAG = "Network"
    }
}
