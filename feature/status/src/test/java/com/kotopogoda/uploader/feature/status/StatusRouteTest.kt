package com.kotopogoda.uploader.feature.status

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.network.health.HealthStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StatusRouteTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val viewModel: StatusViewModel = mockk(relaxed = true)
    private val events = MutableSharedFlow<StatusEvent>(replay = 0, extraBufferCapacity = 1)
    private val uiState = MutableStateFlow(
        StatusUiState(
            health = HealthState(
                status = HealthStatus.ONLINE,
                lastCheckedAt = null,
                message = null,
                latencyMillis = 200L,
            ),
            pairing = PairingStatus.Unknown,
            queue = QueueSummary.Empty,
            storage = StorageStatus.Loading,
        )
    )

    @Before
    fun setup() {
        every { viewModel.uiState } returns uiState
        every { viewModel.events } returns events
    }

    @Test
    fun showsSnackbarAfterManualPing() {
        val expectedLatency = 150L
        every { viewModel.onRefreshHealth() } answers {
            events.tryEmit(
                StatusEvent.HealthPingResult(
                    isSuccess = true,
                    latencyMillis = expectedLatency,
                    error = null,
                )
            )
        }

        composeTestRule.setContent {
            StatusRoute(
                onBack = {},
                onOpenQueue = {},
                onOpenPairingSettings = {},
                isNetworkValidated = true,
                viewModel = viewModel,
            )
        }

        val refreshDescription = composeTestRule.activity.getString(R.string.status_refresh_health)
        composeTestRule.onNodeWithContentDescription(refreshDescription).performClick()

        val successMessage = composeTestRule.activity.getString(
            R.string.status_health_ping_success,
            composeTestRule.activity.getString(R.string.status_health_latency_ms, expectedLatency)
        )

        composeTestRule.waitUntilExactlyOneExists(hasText(successMessage))
        composeTestRule.onNodeWithText(successMessage).assertExists()
    }
}
