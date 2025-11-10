package com.kotopogoda.uploader.core.network.health

import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.network.health.HealthStatus.DEGRADED
import com.kotopogoda.uploader.core.network.health.HealthStatus.OFFLINE
import com.kotopogoda.uploader.core.network.health.HealthStatus.ONLINE
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import retrofit2.converter.moshi.MoshiConverterFactory

@OptIn(ExperimentalCoroutinesApi::class)
class HealthRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var networkClientProvider: NetworkClientProvider

    @Before
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        networkClientProvider = NetworkClientProvider(
            okHttpClient = OkHttpClient(),
            converterFactory = MoshiConverterFactory.create(),
            defaultBaseUrl = mockWebServer.url("/").toString(),
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `check maps string status`() = runTest {
        enqueueResponse("""{"status":"ok","message":"ok"}""")

        val repository = repository(
            clock = sequenceClock(Instant.EPOCH, Instant.EPOCH.plusMillis(42)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.check()

        assertEquals(ONLINE, result.status)
        assertEquals("ok", result.message)
        assertEquals(42L, result.latencyMillis)
    }

    @Test
    fun `check maps numeric status`() = runTest {
        enqueueResponse("""{"status":0}""")

        val repository = repository(
            clock = sequenceClock(Instant.EPOCH, Instant.EPOCH.plusMillis(10)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.check()

        assertEquals(OFFLINE, result.status)
        assertEquals(10L, result.latencyMillis)
    }

    @Test
    fun `check maps nested status`() = runTest {
        enqueueResponse("""{"status":{"overall":"degraded"}}""")

        val repository = repository(
            clock = sequenceClock(Instant.EPOCH, Instant.EPOCH.plusMillis(7)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.check()

        assertEquals(DEGRADED, result.status)
        assertEquals(7L, result.latencyMillis)
    }

    @Test
    fun `check maps http code statuses`() = runTest {
        enqueueResponse("""{"status":200}""")

        val repository = repository(
            clock = sequenceClock(Instant.EPOCH, Instant.EPOCH.plusMillis(13)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.check()

        assertEquals(ONLINE, result.status)
        assertEquals(13L, result.latencyMillis)
    }

    @Test
    fun `check maps boolean ok flag`() = runTest {
        enqueueResponse("""{"ok":true}""")

        val repository = repository(
            clock = sequenceClock(Instant.EPOCH, Instant.EPOCH.plusMillis(15)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.check()

        assertEquals(ONLINE, result.status)
        assertEquals(15L, result.latencyMillis)
    }

    @Test
    fun `check falls back to ONLINE with parse error for unparsable status`() = runTest {
        enqueueResponse("""{"status":{"unexpected":{}}}""")

        val repository = repository(
            clock = sequenceClock(Instant.EPOCH, Instant.EPOCH.plusMillis(5)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.check()

        assertEquals(ONLINE, result.status)
        assertEquals(HealthState.MESSAGE_PARSE_ERROR, result.message)
    }

    @Test
    fun `check treats unknown string status as parse error`() = runTest {
        enqueueResponse("""{"status":"green"}""")

        val repository = repository(
            clock = sequenceClock(Instant.EPOCH, Instant.EPOCH.plusMillis(8)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.check()

        assertEquals(ONLINE, result.status)
        assertEquals(HealthState.MESSAGE_PARSE_ERROR, result.message)
    }

    @Test
    fun `check treats unknown numeric status as parse error`() = runTest {
        enqueueResponse("""{"status":999}""")

        val repository = repository(
            clock = sequenceClock(Instant.EPOCH, Instant.EPOCH.plusMillis(9)),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val result = repository.check()

        assertEquals(ONLINE, result.status)
        assertEquals(HealthState.MESSAGE_PARSE_ERROR, result.message)
    }

    private fun repository(clock: Clock, dispatcher: CoroutineDispatcher): HealthRepository {
        return HealthRepository(
            networkClientProvider = networkClientProvider,
            clock = clock,
            ioDispatcher = dispatcher,
        )
    }

    private fun enqueueResponse(body: String) {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun sequenceClock(vararg instants: Instant): Clock {
        return object : Clock() {
            private val zone: ZoneId = ZoneId.of("UTC")
            private val queue = ArrayDeque(instants.toList())
            private var last: Instant = instants.lastOrNull() ?: Instant.EPOCH

            override fun getZone(): ZoneId = zone

            override fun withZone(zone: ZoneId): Clock = this

            override fun instant(): Instant {
                val next = if (queue.isEmpty()) null else queue.removeFirst()
                if (next != null) {
                    last = next
                    return next
                }
                return last
            }
        }
    }
}
