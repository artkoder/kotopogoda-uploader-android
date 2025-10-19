package com.kotopogoda.uploader.feature.pairing.domain

import com.kotopogoda.uploader.core.network.api.AttachDeviceResponse
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import com.kotopogoda.uploader.feature.pairing.data.PairingRepository
import com.kotopogoda.uploader.feature.pairing.logging.PairingLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelLoggingTest {

    private val repository = mockk<PairingRepository>()
    private val credsStore = mockk<DeviceCredsStore>(relaxed = true)
    private val pairingLogger = mockk<PairingLogger>(relaxed = true)
    private val timberTree = RecordingTree()

    @Before
    fun setUp() {
        Timber.uprootAll()
        Timber.plant(timberTree)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        clearMocks(repository, credsStore, pairingLogger)
    }

    @Test
    fun `writes success logs to pairing logger and timber`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val response = AttachDeviceResponse(deviceId = "DEVICE1234", hmacKey = "secret")
            coEvery { repository.attach("TOKEN23") } returns response

            val viewModel = createViewModel()

            viewModel.attach("token23")
            advanceUntilIdle()

            coVerify(exactly = 1) { pairingLogger.log("Attach succeeded device=1234") }
            assertTrue(
                timberTree.loggedWithPriority(RecordingTree.INFO)
                    .contains("Attach succeeded device=1234"),
                "Должна быть запись уровня INFO в Timber"
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `writes failure logs to pairing logger and timber`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            coEvery { repository.attach("TOKEN23") } throws IllegalStateException("Boom")

            val viewModel = createViewModel()

            viewModel.attach("token23")
            advanceUntilIdle()

            coVerify(exactly = 1) { pairingLogger.log("Attach failed reason=Boom") }
            assertTrue(
                timberTree.loggedWithPriority(RecordingTree.WARN)
                    .contains("Attach failed reason=Boom"),
                "Должна быть запись уровня WARN в Timber"
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(): PairingViewModel {
        return PairingViewModel(repository, credsStore, pairingLogger)
    }

    private class RecordingTree : Timber.Tree() {

        private val events = mutableListOf<Pair<Int, String>>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (tag == "Pairing") {
                events += priority to message
            }
        }

        fun loggedWithPriority(priority: Int): List<String> {
            return events.filter { it.first == priority }.map { it.second }
        }

        companion object {
            const val INFO = 4
            const val WARN = 5
        }
    }
}
