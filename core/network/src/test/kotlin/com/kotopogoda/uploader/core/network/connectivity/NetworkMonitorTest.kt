package com.kotopogoda.uploader.core.network.connectivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import androidx.test.core.app.ApplicationProvider
import com.kotopogoda.uploader.core.network.health.HealthMonitor
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkMonitorTest {

    private val connectivityManager: ConnectivityManager = mockk()
    private val healthMonitor: HealthMonitor = mockk(relaxed = true)
    private lateinit var monitor: NetworkMonitor
    private lateinit var context: TestContext

    @BeforeTest
    fun setUp() {
        context = TestContext(ApplicationProvider.getApplicationContext())
        every { connectivityManager.registerDefaultNetworkCallback(any()) } just Runs
        every { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) } just Runs
        every { connectivityManager.activeNetwork } returns null
        monitor = NetworkMonitor(context, connectivityManager, healthMonitor)
    }

    @AfterTest
    fun tearDown() {
        monitor.stop()
    }

    @Test
    fun `emits connectivity changes from callbacks`() {
        val callbackSlot = slot<ConnectivityManager.NetworkCallback>()
        every { connectivityManager.registerDefaultNetworkCallback(capture(callbackSlot)) } just Runs

        monitor.start()

        assertFalse(monitor.isNetworkValidated.value)

        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true

        callbackSlot.captured.onAvailable(network)

        assertTrue(monitor.isNetworkValidated.value)
        verify { healthMonitor.refreshNow() }

        every { connectivityManager.activeNetwork } returns null
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false

        callbackSlot.captured.onLost(network)

        assertFalse(monitor.isNetworkValidated.value)
    }

    private class TestContext(base: Context) : ContextWrapper(base) {
        var receiver: BroadcastReceiver? = null

        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
            this.receiver = receiver
            return null
        }

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            broadcastPermission: String?,
            scheduler: Handler?
        ): Intent? {
            this.receiver = receiver
            return null
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter?,
            flags: Int
        ): Intent? {
            this.receiver = receiver
            return null
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            if (this.receiver == receiver) {
                this.receiver = null
            }
        }
    }
}
