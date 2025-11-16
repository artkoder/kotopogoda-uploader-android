package com.kotopogoda.uploader.feature.viewer

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.data.deletion.DeletionConfirmationUiState
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.feature.viewer.R
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

        val scrolledToNewest = mutableStateOf(false)

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
                selection = emptySet(),
                isSelectionMode = false,
                observeUploadEnqueued = { flowOf(false) },
                observeDeletionQueued = { flowOf(false) },
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                deletionConfirmationUiState = DeletionConfirmationUiState(),
                onConfirmDeletion = {},
                deletionConfirmationEvents = emptyFlow(),
                deletionPermissionsLauncher = mockk(relaxed = true),
                onLaunchDeletionBatch = {},
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = {},
                onEnqueueUpload = { _ -> },
                onEnqueueDeletion = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = {},
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = { scrolledToNewest.value = true },
                onPhotoLongPress = {},
                onToggleSelection = {},
                onCancelSelection = {},
                onSelectFolder = {},
                enhancementStrength = 0.5f,
                enhancementInProgress = false,
                enhancementReady = true,
                enhancementResultUri = null,
                isEnhancementResultForCurrentPhoto = false,
                enhancementProgress = emptyMap(),
                onEnhancementStrengthChange = {},
                onEnhancementStrengthChangeFinished = {}
            )
        }

        val goToNewestLabel =
            composeRule.activity.getString(R.string.viewer_open_latest)
        composeRule.onNodeWithContentDescription(goToNewestLabel).performClick()
        composeRule.runOnIdle {
            assertTrue(scrolledToNewest.value)
        }

        composeRule.onNodeWithText(
            composeRule.activity.getString(R.string.viewer_empty_title)
        ).assertDoesNotExist()
    }

    @Test
    fun longPressEnablesSelectionAndBatchActions() {
        val photos = listOf(
            PhotoItem(id = "1", uri = Uri.parse("content://photo/1"), takenAt = null),
            PhotoItem(id = "2", uri = Uri.parse("content://photo/2"), takenAt = null),
            PhotoItem(id = "3", uri = Uri.parse("content://photo/3"), takenAt = null)
        )

        composeRule.setContent {
            val pagingItems = flowOf(PagingData.from(photos)).collectAsLazyPagingItems()
            var selection by remember { mutableStateOf(setOf<PhotoItem>()) }
            var moved by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
            var deleted by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
            ViewerScreen(
                photos = pagingItems,
                currentIndex = 0,
                isPagerScrollEnabled = true,
                undoCount = 0,
                canUndo = false,
                actionInProgress = null,
                events = emptyFlow(),
                selection = selection,
                isSelectionMode = selection.isNotEmpty(),
                observeUploadEnqueued = { flowOf(false) },
                observeDeletionQueued = { flowOf(false) },
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                deletionConfirmationUiState = DeletionConfirmationUiState(),
                onConfirmDeletion = {},
                deletionConfirmationEvents = emptyFlow(),
                deletionPermissionsLauncher = mockk(relaxed = true),
                onLaunchDeletionBatch = {},
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = { moved = selection.toList() },
                onEnqueueUpload = { _ -> },
                onEnqueueDeletion = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = { deleted = selection.toList() },
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = {},
                onPhotoLongPress = { photo -> selection = setOf(photo) },
                onToggleSelection = { photo ->
                    selection = if (photo in selection) {
                        selection - photo
                    } else {
                        selection + photo
                    }
                },
                onCancelSelection = { selection = emptySet() },
                onSelectFolder = {},
                enhancementStrength = 0.5f,
                enhancementInProgress = false,
                enhancementReady = true,
                enhancementResultUri = null,
                isEnhancementResultForCurrentPhoto = false,
                enhancementProgress = emptyMap(),
                onEnhancementStrengthChange = {},
                onEnhancementStrengthChangeFinished = {}
            )
        }

        composeRule.onNodeWithTag("viewer_photo_0").performTouchInput { longClick() }
        val selectionOne = composeRule.activity.getString(R.string.viewer_selection_count, 1)
        composeRule.onNodeWithText(selectionOne).assertExists()

        composeRule.onNodeWithTag("viewer_selection_1").performClick()
        val selectionTwo = composeRule.activity.getString(R.string.viewer_selection_count, 2)
        composeRule.onNodeWithText(selectionTwo).assertExists()

        val processingLabel = composeRule.activity.getString(R.string.viewer_action_processing)
        composeRule.onNodeWithText(processingLabel).performClick()
        composeRule.runOnIdle {
            assertEquals(2, moved.size)
        }

        val deleteLabel = composeRule.activity.getString(R.string.viewer_action_delete)
        composeRule.onNodeWithText(deleteLabel).performClick()
        composeRule.runOnIdle {
            assertEquals(2, deleted.size)
        }
    }
}
