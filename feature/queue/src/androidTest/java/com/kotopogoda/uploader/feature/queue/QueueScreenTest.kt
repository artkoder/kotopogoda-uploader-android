package com.kotopogoda.uploader.feature.queue

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.kotopogoda.uploader.core.data.upload.UploadItemEntity
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.data.upload.UploadQueueEntry
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.core.network.health.HealthStatus
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.feature.queue.R
import com.kotopogoda.uploader.feature.queue.toQueueItemUiModel
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

    @Test
    fun queueDisplaysAuthErrorAndRetryAction() {
        val entity = UploadItemEntity(
            id = 10L,
            photoId = "photo-10",
            idempotencyKey = "auth-10",
            uri = "content://photos/10",
            displayName = "auth.jpg",
            size = 1_000L,
            state = UploadItemState.FAILED.rawValue,
            createdAt = 0L,
            updatedAt = 0L,
            lastErrorKind = UploadErrorKind.AUTH.rawValue,
            httpCode = 401,
            lastErrorMessage = null,
        )
        val entry = UploadQueueEntry(
            entity = entity,
            uri = null,
            state = UploadItemState.FAILED,
            lastErrorKind = UploadErrorKind.AUTH,
            lastErrorHttpCode = 401,
            lastErrorMessage = null,
        )
        val item = entry.toQueueItemUiModel(workInfo = null)

        composeRule.setContent {
            QueueScreen(
                state = QueueUiState(items = listOf(item)),
                healthState = HealthState(status = HealthStatus.ONLINE),
                isNetworkValidated = true,
                onBack = {},
                onCancel = {},
                onRetry = {},
            )
        }

        val expectedMessage = composeRule.activity.getString(
            R.string.queue_last_error,
            composeRule.activity.getString(R.string.queue_error_auth)
        )

        composeRule.onNodeWithText(expectedMessage).assertIsDisplayed()
        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.queue_action_retry)
        ).assertIsDisplayed()
    }
}
