package com.kotopogoda.uploader.core.network.connectivity

import android.net.ConnectivityManager
import android.net.Network
import com.kotopogoda.uploader.core.network.health.HealthMonitor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityObserver @Inject constructor(
    private val connectivityManager: ConnectivityManager,
    private val healthMonitor: HealthMonitor,
) {
    private val registered = AtomicBoolean(false)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            healthMonitor.refreshNow()
        }
    }

    fun start() {
        if (registered.compareAndSet(false, true)) {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
    }

    fun stop() {
        if (registered.compareAndSet(true, false)) {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}
