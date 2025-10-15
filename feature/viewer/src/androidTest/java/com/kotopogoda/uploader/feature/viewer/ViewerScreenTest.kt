package com.kotopogoda.uploader.feature.viewer

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.feature.viewer.R
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ViewerScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun viewerDisplaysContentWhenPhotosAvailable() {
        val photo = PhotoItem(
            id = "id",
            uri = Uri.parse("content://photo/1"),
            takenAt = null
        )

        composeRule.setContent {
            val pagingItems = flowOf(PagingData.from(listOf(photo))).collectAsLazyPagingItems()
            ViewerScreen(
                photos = pagingItems,
                currentIndex = 0,
                isPagerScrollEnabled = true,
                undoCount = 0,
                canUndo = false,
                actionInProgress = null,
                events = emptyFlow(),
                observeUploadEnqueued = { flowOf(false) },
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onEnqueueUpload = { _ -> },
                onUndo = {},
                onJumpToDate = {}
            )
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.viewer_empty_title)
        ).assertDoesNotExist()
    }
}
