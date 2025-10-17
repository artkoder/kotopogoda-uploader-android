package com.kotopogoda.uploader.feature.queue

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.network.health.HealthStatus
import com.kotopogoda.uploader.feature.queue.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class QueueScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun queueDisplaysOfflineNoticeWhenNetworkUnavailable() {
        composeRule.setContent {
            QueueScreen(
                state = QueueUiState(),
                healthState = HealthState(status = HealthStatus.ONLINE),
                isNetworkValidated = false,
                onBack = {},
                onCancel = {},
                onRetry = {}
            )
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.queue_offline_notice)
        ).assertIsDisplayed()

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.queue_health_offline)
        ).assertIsDisplayed()
    }
}
