package com.kotopogoda.uploader.core.network.upload

import androidx.work.NetworkType
import androidx.work.WorkManager
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class UploadEnqueuerTest {

    private val workManager = mockk<WorkManager>(relaxed = true)
    private val summaryStarter = mockk<UploadSummaryStarter>(relaxed = true)

    @Test
    fun networkConstraints_connected_whenWifiOnlyDisabled() = runBlocking {
        val wifiOnlyFlow = MutableStateFlow(false)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, wifiOnlyFlow)

        assertNetworkTypeEventually(enqueuer, NetworkType.CONNECTED)
    }

    @Test
    fun networkConstraints_unmetered_whenWifiOnlyEnabled() = runBlocking {
        val wifiOnlyFlow = MutableStateFlow(true)
        val enqueuer = UploadEnqueuer(workManager, summaryStarter, wifiOnlyFlow)

        assertNetworkTypeEventually(enqueuer, NetworkType.UNMETERED)
    }

    private suspend fun assertNetworkTypeEventually(
        enqueuer: UploadEnqueuer,
        expected: NetworkType,
    ) {
        withTimeout(TimeUnit.SECONDS.toMillis(1)) {
            while (true) {
                val constraints = enqueuer.networkConstraints()
                if (constraints.requiredNetworkType == expected) {
                    return@withTimeout
                }
                delay(10)
            }
        }
        throw AssertionError("Expected network type $expected")
    }
}
