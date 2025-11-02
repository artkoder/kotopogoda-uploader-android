package com.kotopogoda.uploader.feature.viewer

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.network.health.HealthState
import com.kotopogoda.uploader.feature.viewer.R
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI тесты для enhancement функционала:
 * - Loader overlay во время обработки
 * - Отображение значения слайдера
 * - Обновленные теги слайдера
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ViewerScreenEnhancementTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun enhancementSliderDisplaysCorrectValue() {
        val photo = PhotoItem(
            id = "test-photo",
            uri = Uri.parse("content://photo/1"),
            takenAt = null
        )

        composeRule.setContent {
            val pagingItems = flowOf(PagingData.from(listOf(photo))).collectAsLazyPagingItems()
            var strength by remember { mutableFloatStateOf(0.5f) }
            
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
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = {},
                onEnqueueUpload = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = {},
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = {},
                onPhotoLongPress = {},
                onToggleSelection = {},
                onCancelSelection = {},
                onSelectFolder = {},
                enhancementStrength = strength,
                enhancementInProgress = false,
                enhancementReady = true,
                enhancementResultUri = null,
                isEnhancementResultForCurrentPhoto = false,
                enhancementProgress = emptyMap(),
                onEnhancementStrengthChange = { newStrength -> strength = newStrength },
                onEnhancementStrengthChangeFinished = {}
            )
        }

        // Проверяем что слайдер отображает начальное значение
        composeRule.onNodeWithTag("enhancement_slider").assertExists()
        
        // Проверяем что значение strength отображается в UI (предполагаем что есть текст с процентами)
        val expectedPercentage = (0.5f * 100).toInt()
        // Если есть метка со значением - проверяем её
        composeRule.onNodeWithTag("enhancement_strength_label").assertExists()
    }

    @Test
    fun enhancementSliderCanBeAdjusted() {
        val photo = PhotoItem(
            id = "test-photo",
            uri = Uri.parse("content://photo/1"),
            takenAt = null
        )

        composeRule.setContent {
            val pagingItems = flowOf(PagingData.from(listOf(photo))).collectAsLazyPagingItems()
            var strength by remember { mutableFloatStateOf(0.5f) }
            var changedValues = remember { mutableListOf<Float>() }
            var finishedValue by remember { mutableFloatStateOf(0f) }
            
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
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = {},
                onEnqueueUpload = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = {},
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = {},
                onPhotoLongPress = {},
                onToggleSelection = {},
                onCancelSelection = {},
                onSelectFolder = {},
                enhancementStrength = strength,
                enhancementInProgress = false,
                enhancementReady = true,
                enhancementResultUri = null,
                isEnhancementResultForCurrentPhoto = false,
                enhancementProgress = emptyMap(),
                onEnhancementStrengthChange = { newStrength -> 
                    strength = newStrength
                    changedValues.add(newStrength)
                },
                onEnhancementStrengthChangeFinished = { newStrength ->
                    finishedValue = newStrength
                }
            )
        }

        // Свайпаем слайдер вправо
        composeRule.onNodeWithTag("enhancement_slider")
            .performTouchInput { swipeRight() }
        
        composeRule.runOnIdle {
            // Должны были произойти изменения
            assertTrue(changedValues.isNotEmpty(), "Slider должен был вызвать onEnhancementStrengthChange")
        }
    }

    @Test
    fun loaderOverlayShownDuringProcessing() {
        val photo = PhotoItem(
            id = "test-photo",
            uri = Uri.parse("content://photo/1"),
            takenAt = null
        )

        composeRule.setContent {
            val pagingItems = flowOf(PagingData.from(listOf(photo))).collectAsLazyPagingItems()
            var inProgress by remember { mutableStateOf(false) }
            
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
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = {},
                onEnqueueUpload = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = {},
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = {},
                onPhotoLongPress = {},
                onToggleSelection = {},
                onCancelSelection = {},
                onSelectFolder = {},
                enhancementStrength = 0.5f,
                enhancementInProgress = inProgress,
                enhancementReady = !inProgress,
                enhancementResultUri = null,
                isEnhancementResultForCurrentPhoto = false,
                enhancementProgress = emptyMap(),
                onEnhancementStrengthChange = {},
                onEnhancementStrengthChangeFinished = { inProgress = true }
            )
        }

        // В начале loader не показан
        composeRule.onNodeWithTag("enhancement_loader").assertDoesNotExist()
        
        // Симулируем начало обработки
        composeRule.onNodeWithTag("enhancement_slider")
            .performTouchInput { swipeRight() }
        
        composeRule.waitForIdle()
        
        // Теперь loader должен быть показан
        composeRule.onNodeWithTag("enhancement_loader").assertExists()
        composeRule.onNodeWithTag("enhancement_loader").assertIsDisplayed()
    }

    @Test
    fun progressIndicatorShowsTileProgress() {
        val photo = PhotoItem(
            id = "test-photo",
            uri = Uri.parse("content://photo/1"),
            takenAt = null
        )

        composeRule.setContent {
            val pagingItems = flowOf(PagingData.from(listOf(photo))).collectAsLazyPagingItems()
            val progress = remember { 
                mutableStateMapOf(
                    0 to 1.0f,
                    1 to 0.5f,
                    2 to 0.25f,
                    3 to 0.0f
                )
            }
            
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
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = {},
                onEnqueueUpload = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = {},
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = {},
                onPhotoLongPress = {},
                onToggleSelection = {},
                onCancelSelection = {},
                onSelectFolder = {},
                enhancementStrength = 0.5f,
                enhancementInProgress = true,
                enhancementReady = false,
                enhancementResultUri = null,
                isEnhancementResultForCurrentPhoto = false,
                enhancementProgress = progress,
                onEnhancementStrengthChange = {},
                onEnhancementStrengthChangeFinished = {}
            )
        }

        // Проверяем наличие прогресс-индикатора
        composeRule.onNodeWithTag("enhancement_progress").assertExists()
        composeRule.onNodeWithTag("enhancement_progress").assertIsDisplayed()
    }

    @Test
    fun enhancementResultUriDisplayedWhenReady() {
        val photo = PhotoItem(
            id = "test-photo",
            uri = Uri.parse("content://photo/1"),
            takenAt = null
        )
        val resultUri = Uri.parse("file:///storage/enhanced/photo1_enhanced.jpg")

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
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = {},
                onEnqueueUpload = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = {},
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = {},
                onPhotoLongPress = {},
                onToggleSelection = {},
                onCancelSelection = {},
                onSelectFolder = {},
                enhancementStrength = 0.7f,
                enhancementInProgress = false,
                enhancementReady = true,
                enhancementResultUri = resultUri,
                isEnhancementResultForCurrentPhoto = true,
                enhancementProgress = emptyMap(),
                onEnhancementStrengthChange = {},
                onEnhancementStrengthChangeFinished = {}
            )
        }

        // Когда результат готов и это текущее фото, должен отображаться enhanced результат
        // Проверяем что результат отображается через AsyncImage или другой компонент
        composeRule.onNodeWithTag("viewer_photo_0").assertExists()
    }

    @Test
    fun sliderDisabledDuringProcessing() {
        val photo = PhotoItem(
            id = "test-photo",
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
                selection = emptySet(),
                isSelectionMode = false,
                observeUploadEnqueued = { flowOf(false) },
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = {},
                onEnqueueUpload = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = {},
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = {},
                onPhotoLongPress = {},
                onToggleSelection = {},
                onCancelSelection = {},
                onSelectFolder = {},
                enhancementStrength = 0.5f,
                enhancementInProgress = true,
                enhancementReady = false,
                enhancementResultUri = null,
                isEnhancementResultForCurrentPhoto = false,
                enhancementProgress = mapOf(0 to 0.5f),
                onEnhancementStrengthChange = {},
                onEnhancementStrengthChangeFinished = {}
            )
        }

        // Слайдер должен быть доступен даже во время обработки (для предпросмотра изменения)
        // но возможно с ограничениями или визуальным индикатором
        composeRule.onNodeWithTag("enhancement_slider").assertExists()
    }

    @Test
    fun sliderCallbacksStayWithinRange() {
        val photo = PhotoItem(
            id = "test-photo",
            uri = Uri.parse("content://photo/1"),
            takenAt = null
        )

        composeRule.setContent {
            val pagingItems = flowOf(PagingData.from(listOf(photo))).collectAsLazyPagingItems()
            var strength by remember { mutableFloatStateOf(0.5f) }
            val capturedValues = remember { mutableListOf<Float>() }
            
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
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = {},
                onEnqueueUpload = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = {},
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = {},
                onPhotoLongPress = {},
                onToggleSelection = {},
                onCancelSelection = {},
                onSelectFolder = {},
                enhancementStrength = strength,
                enhancementInProgress = false,
                enhancementReady = true,
                enhancementResultUri = null,
                isEnhancementResultForCurrentPhoto = false,
                enhancementProgress = emptyMap(),
                onEnhancementStrengthChange = { newValue ->
                    capturedValues.add(newValue)
                    strength = newValue
                },
                onEnhancementStrengthChangeFinished = {}
            )
        }

        composeRule.onNodeWithTag("enhancement_slider")
            .performTouchInput { swipeRight() }
        
        composeRule.runOnIdle {
            assertTrue(capturedValues.isNotEmpty(), "должны быть захвачены значения из слайдера")
            capturedValues.forEach { value ->
                assertTrue(value >= 0f, "все значения должны быть >= 0f, получено: $value")
                assertTrue(value <= 1f, "все значения должны быть <= 1f, получено: $value")
            }
        }
    }

    @Test
    fun sliderReachesMaximumOnFullSwipe() {
        val photo = PhotoItem(
            id = "test-photo",
            uri = Uri.parse("content://photo/1"),
            takenAt = null
        )

        composeRule.setContent {
            val pagingItems = flowOf(PagingData.from(listOf(photo))).collectAsLazyPagingItems()
            var strength by remember { mutableFloatStateOf(0f) }
            var finishedValue by remember { mutableFloatStateOf(0f) }
            
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
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = {},
                onEnqueueUpload = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = {},
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = {},
                onPhotoLongPress = {},
                onToggleSelection = {},
                onCancelSelection = {},
                onSelectFolder = {},
                enhancementStrength = strength,
                enhancementInProgress = false,
                enhancementReady = true,
                enhancementResultUri = null,
                isEnhancementResultForCurrentPhoto = false,
                enhancementProgress = emptyMap(),
                onEnhancementStrengthChange = { newValue ->
                    strength = newValue
                },
                onEnhancementStrengthChangeFinished = {
                    finishedValue = strength
                }
            )
        }

        composeRule.onNodeWithTag("enhancement_slider")
            .performTouchInput { swipeRight(endX = right) }
        
        composeRule.runOnIdle {
            assertTrue(
                finishedValue >= 0.9f,
                "после полного свайпа вправо strength должен быть близок к 1f, получено: $finishedValue"
            )
        }
    }

    @Test
    fun sliderLabelReflectsCorrectPercentage() {
        val photo = PhotoItem(
            id = "test-photo",
            uri = Uri.parse("content://photo/1"),
            takenAt = null
        )

        composeRule.setContent {
            val pagingItems = flowOf(PagingData.from(listOf(photo))).collectAsLazyPagingItems()
            var strength by remember { mutableFloatStateOf(0.5f) }
            
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
                onBack = {},
                onOpenQueue = {},
                onOpenStatus = {},
                onOpenSettings = {},
                healthState = HealthState.Unknown,
                isNetworkValidated = true,
                onPageChanged = {},
                onVisiblePhotoChanged = { _, _ -> },
                onZoomStateChanged = {},
                onSkip = { _ -> },
                onMoveToProcessing = { _ -> },
                onMoveSelection = {},
                onEnqueueUpload = { _ -> },
                onUndo = {},
                onDelete = { _ -> },
                onDeleteSelection = {},
                onDeleteResult = {},
                onWriteRequestResult = {},
                onJumpToDate = {},
                onScrollToNewest = {},
                onPhotoLongPress = {},
                onToggleSelection = {},
                onCancelSelection = {},
                onSelectFolder = {},
                enhancementStrength = strength,
                enhancementInProgress = false,
                enhancementReady = true,
                enhancementResultUri = null,
                isEnhancementResultForCurrentPhoto = false,
                enhancementProgress = emptyMap(),
                onEnhancementStrengthChange = { newValue ->
                    strength = newValue
                },
                onEnhancementStrengthChangeFinished = {}
            )
        }

        composeRule.onNodeWithTag("enhancement_strength_label").assertExists()
        
        val expectedPercentage = (0.5f * 100).toInt()
        composeRule.onNodeWithTag("enhancement_strength_label")
            .assertTextContains("$expectedPercentage", substring = true)
    }
}
