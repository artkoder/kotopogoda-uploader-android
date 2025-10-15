package com.kotopogoda.uploader.core.network.health

import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.kotopogoda.uploader.core.network.health.HealthStatus.DEGRADED
import com.kotopogoda.uploader.core.network.health.HealthStatus.OFFLINE
import com.kotopogoda.uploader.core.network.health.HealthStatus.ONLINE
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.converter.moshi.MoshiConverterFactory

class HealthMonitorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var healthMonitor: HealthMonitor

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        val provider = NetworkClientProvider(
            okHttpClient = OkHttpClient(),
            converterFactory = MoshiConverterFactory.create(),
            defaultBaseUrl = mockWebServer.url("/").toString(),
        )
        healthMonitor = HealthMonitor(provider)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `checkOnce maps offline status to OFFLINE`() = runTest {
        enqueueHealthResponse("offline")

        healthMonitor.checkOnce()

        assertEquals(OFFLINE, healthMonitor.state.value.status)
    }

    @Test
    fun `checkOnce maps degraded status to DEGRADED`() = runTest {
        enqueueHealthResponse("degraded")

        healthMonitor.checkOnce()

        assertEquals(DEGRADED, healthMonitor.state.value.status)
    }

    @Test
    fun `checkOnce keeps tolerant handling for unknown status`() = runTest {
        enqueueHealthResponse("unexpected")

        healthMonitor.checkOnce()

        assertEquals(ONLINE, healthMonitor.state.value.status)
    }

    private fun enqueueHealthResponse(status: String) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"status":"$status","message":"test"}"""),
        )
    }
}
