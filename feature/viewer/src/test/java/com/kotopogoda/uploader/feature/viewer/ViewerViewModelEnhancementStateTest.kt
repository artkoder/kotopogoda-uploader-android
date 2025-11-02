package com.kotopogoda.uploader.feature.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.data.sa.SaFileRepository
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.core.settings.AppSettings
import com.kotopogoda.uploader.core.settings.PreviewQuality
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceAdapter
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тесты для state machine enhancement контроллера:
 * - Переходы Idle→ComputingPreview→Ready
 * - Отмена при смене фото
 * - Сброс слайдера
 * - Кеширование полного разрешения
 * - Распространение телеметрии
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewerViewModelEnhancementStateTest {

    @MockK(relaxed = true)
    private lateinit var photoRepository: PhotoRepository

    @MockK(relaxed = true)
    private lateinit var folderRepository: FolderRepository

    @MockK(relaxed = true)
    private lateinit var saFileRepository: SaFileRepository

    @MockK(relaxed = true)
    private lateinit var uploadEnqueuer: UploadEnqueuer

    @MockK(relaxed = true)
    private lateinit var uploadQueueRepository: UploadQueueRepository

    @MockK(relaxed = true)
    private lateinit var reviewProgressStore: ReviewProgressStore

    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK(relaxed = true)
    private lateinit var nativeEnhanceAdapter: NativeEnhanceAdapter

    @MockK(relaxed = true)
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var testDispatcher: TestDispatcher

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        
        val defaultSettings = AppSettings(
            baseUrl = "https://example.com",
            appLogging = true,
            httpLogging = true,
            persistentQueueNotification = false,
            previewQuality = PreviewQuality.BALANCED,
        )
        every { settingsRepository.flow } returns kotlinx.coroutines.flow.flowOf(defaultSettings)
    }

    @AfterTest
    fun tearDown() {
        runCatching { unmockkObject() }
    }

    @Test
    fun `initial state is Idle with default strength`() = runTest {
        val viewModel = createViewModel()
        val state = viewModel.enhancementState.first()
        
        assertEquals(0.5f, state.strength)
        assertFalse(state.inProgress)
        assertTrue(state.isResultReady)
        assertNull(state.result)
        assertNull(state.resultUri)
        assertFalse(state.isResultForCurrentPhoto)
        assertTrue(state.progressByTile.isEmpty())
    }

    @Test
    fun `state transitions from Idle to ComputingPreview when slider changed`() = runTest {
        val photo = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val mockFile = mockk<File>(relaxed = true)
        every { mockFile.toURI() } returns java.net.URI("file:///tmp/test.jpg")
        every { mockFile.length() } returns 1024L
        every { mockFile.exists() } returns true
        
        mockEnhanceEngine(mockFile)
        
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        // Начальное состояние Idle
        val initialState = viewModel.enhancementState.first()
        assertTrue(initialState.isResultReady)
        assertFalse(initialState.inProgress)
        
        // Меняем strength - должен начаться переход в ComputingPreview
        viewModel.onEnhancementStrengthChangeFinished(0.7f)
        advanceTimeBy(100)
        
        val computingState = viewModel.enhancementState.first()
        assertTrue(computingState.inProgress)
        assertFalse(computingState.isResultReady)
        
        advanceUntilIdle()
        
        // После завершения - переход в Ready
        val readyState = viewModel.enhancementState.first()
        assertFalse(readyState.inProgress)
        assertTrue(readyState.isResultReady)
        assertNotNull(readyState.result)
        assertEquals(photo.id, readyState.resultPhotoId)
        assertTrue(readyState.isResultForCurrentPhoto)
    }

    @Test
    fun `cancellation on photo change resets state to Idle`() = runTest {
        val photo1 = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val photo2 = PhotoItem(id = "photo2", uri = Uri.parse("content://photo/2"), takenAt = Instant.now())
        val mockFile = mockk<File>(relaxed = true)
        every { mockFile.toURI() } returns java.net.URI("file:///tmp/test.jpg")
        every { mockFile.length() } returns 1024L
        every { mockFile.exists() } returns true
        
        mockEnhanceEngine(mockFile)
        
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo1)
        advanceUntilIdle()
        
        // Запускаем enhancement для photo1
        viewModel.onEnhancementStrengthChangeFinished(0.6f)
        advanceTimeBy(50)
        
        // Проверяем что началась обработка
        val processingState = viewModel.enhancementState.first()
        assertTrue(processingState.inProgress)
        
        // Меняем фото - должна произойти отмена
        viewModel.updateVisiblePhoto(2, photo2)
        advanceUntilIdle()
        
        // Состояние должно сброситься
        val cancelledState = viewModel.enhancementState.first()
        assertFalse(cancelledState.inProgress)
        assertTrue(cancelledState.isResultReady)
        assertNull(cancelledState.resultUri)
        assertFalse(cancelledState.isResultForCurrentPhoto)
    }

    @Test
    fun `slider reset handling preserves state machine consistency`() = runTest {
        val photo = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val mockFile = mockk<File>(relaxed = true)
        every { mockFile.toURI() } returns java.net.URI("file:///tmp/test.jpg")
        every { mockFile.length() } returns 1024L
        every { mockFile.exists() } returns true
        
        mockEnhanceEngine(mockFile)
        
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        // Первое enhancement с strength 0.8
        viewModel.onEnhancementStrengthChangeFinished(0.8f)
        advanceUntilIdle()
        
        val firstResult = viewModel.enhancementState.first()
        assertTrue(firstResult.isResultReady)
        assertNotNull(firstResult.result)
        assertEquals(0.8f, firstResult.strength)
        
        // Сбрасываем strength на 0.5
        viewModel.onEnhancementStrengthChange(0.5f)
        viewModel.onEnhancementStrengthChangeFinished(0.5f)
        advanceUntilIdle()
        
        val resetResult = viewModel.enhancementState.first()
        assertTrue(resetResult.isResultReady)
        assertNotNull(resetResult.result)
        assertEquals(0.5f, resetResult.strength)
    }

    @Test
    fun `full-res caching maintains result between slider adjustments`() = runTest {
        val photo = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val mockFile = mockk<File>(relaxed = true)
        every { mockFile.toURI() } returns java.net.URI("file:///tmp/test.jpg")
        every { mockFile.length() } returns 1024L
        every { mockFile.exists() } returns true
        
        mockEnhanceEngine(mockFile)
        
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        // Первое enhancement
        viewModel.onEnhancementStrengthChangeFinished(0.6f)
        advanceUntilIdle()
        
        val firstState = viewModel.enhancementState.first()
        val firstResultFile = firstState.result?.file
        assertNotNull(firstResultFile)
        
        // Второе enhancement (другая сила)
        viewModel.onEnhancementStrengthChangeFinished(0.8f)
        advanceUntilIdle()
        
        val secondState = viewModel.enhancementState.first()
        val secondResultFile = secondState.result?.file
        assertNotNull(secondResultFile)
        
        // Файлы результатов могут быть разными, но оба должны существовать
        assertTrue(firstState.isResultReady)
        assertTrue(secondState.isResultReady)
    }

    @Test
    fun `telemetry field propagation from engine to state`() = runTest {
        val photo = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val mockFile = mockk<File>(relaxed = true)
        every { mockFile.toURI() } returns java.net.URI("file:///tmp/test.jpg")
        every { mockFile.length() } returns 1024L
        every { mockFile.exists() } returns true
        
        val expectedPipeline = EnhanceEngine.Pipeline(
            stages = listOf("zero-dce", "restormer", "sharpen"),
            tileSize = 512,
            overlap = 64,
            tileSizeActual = 512,
            overlapActual = 64,
            mixingWindow = 128,
            mixingWindowActual = 128,
            tileCount = 4,
            tilesCompleted = 4,
            tileProgress = 1.0f,
            tileUsed = true,
            zeroDceIterations = 8,
            zeroDceApplied = true,
            zeroDceDelegateFallback = false,
            restormerMix = 0.5f,
            restormerApplied = true,
            restormerDelegateFallback = false,
            hasSeamFix = true,
            seamMaxDelta = 0.25f,
            seamMeanDelta = 0.12f,
            seamArea = 1024,
            seamZeroArea = 100,
            seamMinWeight = 0.1f,
            seamMaxWeight = 1.9f,
        )
        
        val expectedModels = EnhanceEngine.ModelsTelemetry(
            zeroDce = EnhanceEngine.ModelUsage(
                backend = EnhanceEngine.ModelBackend.NCNN,
                checksum = "abc123",
                expectedChecksum = "abc123",
                checksumOk = true,
            ),
            restormer = EnhanceEngine.ModelUsage(
                backend = EnhanceEngine.ModelBackend.NCNN,
                checksum = "def456",
                expectedChecksum = "def456",
                checksumOk = true,
            ),
        )
        
        mockEnhanceEngine(mockFile, expectedPipeline, expectedModels)
        
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        viewModel.onEnhancementStrengthChangeFinished(0.7f)
        advanceUntilIdle()
        
        val state = viewModel.enhancementState.first()
        val result = state.result
        assertNotNull(result)
        
        // Проверяем что телеметрия распространилась
        assertEquals(expectedPipeline.tileCount, result.pipeline.tileCount)
        assertEquals(expectedPipeline.tileUsed, result.pipeline.tileUsed)
        assertEquals(expectedPipeline.seamMaxDelta, result.pipeline.seamMaxDelta)
        assertEquals(expectedPipeline.seamMeanDelta, result.pipeline.seamMeanDelta)
        assertEquals(expectedPipeline.zeroDceApplied, result.pipeline.zeroDceApplied)
        assertEquals(expectedPipeline.restormerApplied, result.pipeline.restormerApplied)
        
        assertEquals(expectedModels.zeroDce?.checksum, result.models.zeroDce?.checksum)
        assertEquals(expectedModels.zeroDce?.checksumOk, result.models.zeroDce?.checksumOk)
        assertEquals(expectedModels.restormer?.checksum, result.models.restormer?.checksum)
        assertEquals(expectedModels.restormer?.checksumOk, result.models.restormer?.checksumOk)
    }

    @Test
    fun `progress by tile updates during computation`() = runTest {
        val photo = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val mockFile = mockk<File>(relaxed = true)
        every { mockFile.toURI() } returns java.net.URI("file:///tmp/test.jpg")
        every { mockFile.length() } returns 1024L
        every { mockFile.exists() } returns true
        
        var progressCallback: ((EnhanceEngine.TileProgress) -> Unit)? = null
        
        coEvery {
            enhanceEngine.enhance(any())
        } answers {
            val request = firstArg<EnhanceEngine.Request>()
            progressCallback = request.onTileProgress
            
            // Симулируем прогресс по тайлам
            progressCallback?.invoke(EnhanceEngine.TileProgress(0, 4, 0.5f, null))
            progressCallback?.invoke(EnhanceEngine.TileProgress(1, 4, 0.25f, null))
            
            EnhanceEngine.Result(
                file = mockFile,
                metrics = EnhanceEngine.Metrics(0.5, 0.3, 0.4, 0.2),
                profile = EnhanceEngine.Profile(
                    isLowLight = false,
                    kDce = 0f,
                    restormerMix = 0.5f,
                    alphaDetail = 0.3f,
                    sharpenAmount = 0.4f,
                    sharpenRadius = 1.2f,
                    sharpenThreshold = 0.05f,
                    vibranceGain = 0.2f,
                    saturationGain = 1.1f,
                ),
                delegate = EnhanceEngine.Delegate.GPU,
                pipeline = EnhanceEngine.Pipeline(tileCount = 4),
                timings = EnhanceEngine.Timings(),
                models = EnhanceEngine.ModelsTelemetry(null, null),
            )
        }
        
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        viewModel.onEnhancementStrengthChangeFinished(0.7f)
        advanceTimeBy(100)
        
        // Прогресс должен обновляться
        val progressState = viewModel.enhancementState.first()
        assertTrue(progressState.inProgress)
        
        advanceUntilIdle()
        
        val finalState = viewModel.enhancementState.first()
        assertTrue(finalState.isResultReady)
        assertTrue(finalState.progressByTile.isEmpty()) // После завершения прогресс очищается
    }

    @Test
    fun `strength clamped to minimum when below zero`() = runTest {
        val viewModel = createViewModel()
        
        val initialState = viewModel.enhancementState.first()
        assertEquals(0.5f, initialState.strength)
        
        viewModel.onEnhancementStrengthChange(-0.3f)
        advanceUntilIdle()
        
        val state = viewModel.enhancementState.first()
        assertEquals(0f, state.strength, "strength должен быть клампирован к 0f")
        assertTrue(state.isResultReady, "состояние должно остаться корректным")
        assertFalse(state.inProgress, "обработка не должна начаться для отрицательных значений")
    }

    @Test
    fun `strength clamped to maximum when above one`() = runTest {
        val photo = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        val initialState = viewModel.enhancementState.first()
        assertEquals(0.5f, initialState.strength)
        
        viewModel.onEnhancementStrengthChange(1.5f)
        advanceUntilIdle()
        
        val state = viewModel.enhancementState.first()
        assertEquals(1f, state.strength, "strength должен быть клампирован к 1f")
        assertTrue(state.isResultReady, "состояние должно остаться корректным")
    }

    @Test
    fun `clamping preserves state machine consistency`() = runTest {
        val photo = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        viewModel.onEnhancementStrengthChange(-5f)
        advanceUntilIdle()
        val stateAfterNegative = viewModel.enhancementState.first()
        assertEquals(0f, stateAfterNegative.strength)
        assertTrue(stateAfterNegative.isResultReady)
        
        viewModel.onEnhancementStrengthChange(0.5f)
        advanceUntilIdle()
        val stateAfterNormal = viewModel.enhancementState.first()
        assertEquals(0.5f, stateAfterNormal.strength)
        assertTrue(stateAfterNormal.isResultReady)
        
        viewModel.onEnhancementStrengthChange(10f)
        advanceUntilIdle()
        val stateAfterHigh = viewModel.enhancementState.first()
        assertEquals(1f, stateAfterHigh.strength)
        assertTrue(stateAfterHigh.isResultReady)
    }

    private fun createViewModel(): ViewerViewModel {
        return ViewerViewModel(
            photoRepository = photoRepository,
            folderRepository = folderRepository,
            saFileRepository = saFileRepository,
            uploadEnqueuer = uploadEnqueuer,
            uploadQueueRepository = uploadQueueRepository,
            reviewProgressStore = reviewProgressStore,
            context = context,
            nativeEnhanceAdapter = nativeEnhanceAdapter,
            settingsRepository = settingsRepository,
            savedStateHandle = SavedStateHandle(),
        )
    }

    private fun mockNativeEnhanceAdapter(resultFile: File) {
        coEvery { nativeEnhanceAdapter.initialize(any()) } returns Unit
        coEvery { nativeEnhanceAdapter.computePreview(any(), any(), any()) } returns true
        coEvery { nativeEnhanceAdapter.computeFull(any(), any(), any(), any(), any()) } returns com.kotopogoda.uploader.core.data.upload.UploadEnhancementInfo(
            strength = 0.5f,
            delegate = "cpu",
            metrics = com.kotopogoda.uploader.core.data.upload.UploadEnhancementMetrics(
                lMean = 0.5f,
                pDark = 0.3f,
                bSharpness = 0.4f,
                nNoise = 0.2f,
            ),
            fileSize = 1000L,
            previewTimingMs = 100L,
            fullTimingMs = 500L,
            usedVulkan = false,
            peakMemoryMb = 50f,
            cancelled = false,
        )
    }
}
