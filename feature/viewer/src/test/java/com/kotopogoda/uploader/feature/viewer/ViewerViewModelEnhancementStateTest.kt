package com.kotopogoda.uploader.feature.viewer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.kotopogoda.uploader.core.data.deletion.DeletionQueueRepository
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
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceAdapter
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import java.io.File
import java.time.Instant
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.io.path.createTempDirectory
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
    private lateinit var deletionQueueRepository: DeletionQueueRepository

    @MockK(relaxed = true)
    private lateinit var reviewProgressStore: ReviewProgressStore

    @MockK(relaxed = true)
    private lateinit var context: Context

    @MockK(relaxed = true)
    private lateinit var nativeEnhanceAdapter: NativeEnhanceAdapter

    @MockK(relaxed = true)
    private lateinit var settingsRepository: SettingsRepository

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var cacheDir: File
    private lateinit var contentResolver: ContentResolver

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        cacheDir = createTempDirectory("viewer-enhance").toFile()
        contentResolver = mockk(relaxed = true)

        every { context.cacheDir } returns cacheDir
        every { context.contentResolver } returns contentResolver
        every { contentResolver.persistedUriPermissions } returns emptyList()
        every { contentResolver.openInputStream(any()) } answers {
            ByteArrayInputStream(ByteArray(32) { 0x42 })
        }

        val defaultSettings = AppSettings(
            baseUrl = "https://example.com",
            appLogging = true,
            httpLogging = true,
            persistentQueueNotification = false,
            previewQuality = PreviewQuality.BALANCED,
            autoDeleteAfterUpload = false,
            forceCpuForEnhancement = false,
        )
        every { settingsRepository.flow } returns kotlinx.coroutines.flow.flowOf(defaultSettings)
        every { deletionQueueRepository.observePending() } returns kotlinx.coroutines.flow.flowOf(emptyList())
        coEvery { deletionQueueRepository.getPending() } returns emptyList()
        coEvery { deletionQueueRepository.enqueue(any()) } returns 0
        coEvery { deletionQueueRepository.markSkipped(any()) } returns 0
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        runCatching { cacheDir.deleteRecursively() }
    }

    @Test
    fun `initial state is Idle with default strength`() = runTest {
        val viewModel = createViewModel()
        val state = viewModel.enhancementState.first()
        
        assertEquals(0f, state.strength)
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

        mockNativeEnhanceAdapter(mockFile)

        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        // Начальное состояние Idle
        val initialState = viewModel.enhancementState.first()
        assertTrue(initialState.isResultReady)
        assertFalse(initialState.inProgress)
        
        // Меняем strength - должен начаться переход в ComputingPreview
        viewModel.onEnhancementStrengthChange(0.7f)
        viewModel.onEnhancementStrengthChangeFinished()
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
    fun `changing strength after result triggers new enhancement request`() = runTest {
        val photo = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val mockFile = mockk<File>(relaxed = true)
        every { mockFile.toURI() } returns java.net.URI("file:///tmp/test.jpg")
        every { mockFile.length() } returns 1024L
        every { mockFile.exists() } returns true

        mockNativeEnhanceAdapter(mockFile)

        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()

        viewModel.onEnhancementStrengthChange(0.6f)
        viewModel.onEnhancementStrengthChangeFinished()
        advanceUntilIdle()

        coVerify(exactly = 1) { nativeEnhanceAdapter.computeFull(any(), any(), any(), any(), any()) }

        viewModel.onEnhancementStrengthChange(0.85f)
        viewModel.onEnhancementStrengthChangeFinished()
        advanceUntilIdle()

        coVerify(exactly = 2) { nativeEnhanceAdapter.computeFull(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancellation on photo change resets state to Idle`() = runTest {
        val photo1 = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val photo2 = PhotoItem(id = "photo2", uri = Uri.parse("content://photo/2"), takenAt = Instant.now())
        val mockFile = mockk<File>(relaxed = true)
        every { mockFile.toURI() } returns java.net.URI("file:///tmp/test.jpg")
        every { mockFile.length() } returns 1024L
        every { mockFile.exists() } returns true
        
        mockNativeEnhanceAdapter(mockFile)
        
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo1)
        advanceUntilIdle()
        
        // Запускаем enhancement для photo1
        viewModel.onEnhancementStrengthChange(0.6f)
        viewModel.onEnhancementStrengthChangeFinished()
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
        
        mockNativeEnhanceAdapter(mockFile)
        
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        // Первое enhancement с strength 0.8
        viewModel.onEnhancementStrengthChange(0.8f)
        viewModel.onEnhancementStrengthChangeFinished()
        advanceUntilIdle()
        
        val firstResult = viewModel.enhancementState.first()
        assertTrue(firstResult.isResultReady)
        assertNotNull(firstResult.result)
        assertEquals(0.8f, firstResult.strength)
        
        // Сбрасываем strength на 0.5
        viewModel.onEnhancementStrengthChange(0.5f)
        viewModel.onEnhancementStrengthChange(0.5f)
        viewModel.onEnhancementStrengthChangeFinished()
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
        
        mockNativeEnhanceAdapter(mockFile)
        
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        // Первое enhancement
        viewModel.onEnhancementStrengthChange(0.6f)
        viewModel.onEnhancementStrengthChangeFinished()
        advanceUntilIdle()
        
        val firstState = viewModel.enhancementState.first()
        val firstResultFile = firstState.result?.file
        assertNotNull(firstResultFile)
        
        // Второе enhancement (другая сила)
        viewModel.onEnhancementStrengthChange(0.8f)
        viewModel.onEnhancementStrengthChangeFinished()
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
        
        val expectedModels = EnhanceEngine.ModelsTelemetry(
            zeroDce = EnhanceEngine.ModelUsage(
                backend = EnhanceEngine.ModelBackend.NCNN,
                checksum = "abc123",
                expectedChecksum = "abc123",
                checksumOk = true,
            ),
            restormer = null,
        )
        
        mockNativeEnhanceAdapter(mockFile)
        
        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()
        
        viewModel.onEnhancementStrengthChange(0.7f)
        viewModel.onEnhancementStrengthChangeFinished()
        advanceUntilIdle()
        
        val state = viewModel.enhancementState.first()
        val result = state.result
        assertNotNull(result)

        // Проверяем что телеметрия распространилась
        assertEquals(listOf("native_preview", "native_full"), result.pipeline.stages)
        assertEquals(1, result.pipeline.tileCount)
        assertEquals(1f, result.pipeline.tileProgress)
        assertTrue(result.pipeline.zeroDceApplied)
        assertFalse(result.pipeline.restormerApplied)

        assertEquals(expectedModels.zeroDce?.checksum, result.models?.zeroDce?.checksum)
        assertEquals(expectedModels.zeroDce?.checksumOk, result.models?.zeroDce?.checksumOk)
        assertNull(result.models?.restormer)
        assertNotNull(result.uploadInfo)
        assertEquals(0.5f, result.uploadInfo?.strength)
    }

    @Test
    fun `progress by tile updates during computation`() = runTest {
        val photo = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val mockFile = mockk<File>(relaxed = true)
        every { mockFile.toURI() } returns java.net.URI("file:///tmp/test.jpg")
        every { mockFile.length() } returns 1024L
        every { mockFile.exists() } returns true

        mockNativeEnhanceAdapter(mockFile)

        val viewModel = createViewModel()
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()

        viewModel.onEnhancementStrengthChange(0.7f)
        viewModel.onEnhancementStrengthChangeFinished()
        advanceTimeBy(50)

        val progressState = viewModel.enhancementState.first()
        assertTrue(progressState.inProgress)
        assertEquals(0, progressState.progressByTile.keys.minOrNull())
        assertTrue((progressState.progressByTile[0] ?: 0f) > 0f)

        advanceUntilIdle()

        val finalState = viewModel.enhancementState.first()
        assertTrue(finalState.isResultReady)
        assertTrue(finalState.progressByTile.isEmpty())
    }

    @Test
    fun `strength clamped to minimum when below zero`() = runTest {
        val viewModel = createViewModel()
        
        val initialState = viewModel.enhancementState.first()
        assertEquals(0f, initialState.strength)
        
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
        assertEquals(0f, initialState.strength)
        
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

    @Test
    fun `strength change ignored when enhancement adapter unavailable`() = runTest {
        val photo = PhotoItem(id = "photo1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val viewModel = createViewModel(nativeAdapter = null)
        viewModel.updateVisiblePhoto(1, photo)
        advanceUntilIdle()

        val initialState = viewModel.enhancementState.first()
        assertFalse(viewModel.isEnhancementAvailable.first())

        viewModel.onEnhancementStrengthChange(0.8f)
        viewModel.onEnhancementStrengthChangeFinished()
        advanceUntilIdle()

        val state = viewModel.enhancementState.first()
        assertEquals(initialState, state)
        assertEquals(0f, state.strength)
        assertTrue(state.isResultReady)
        assertFalse(state.inProgress)
        assertTrue(state.progressByTile.isEmpty())
    }

    private fun createViewModel(
        nativeAdapter: NativeEnhanceAdapter? = nativeEnhanceAdapter,
    ): ViewerViewModel {
        return ViewerViewModel(
            photoRepository = photoRepository,
            folderRepository = folderRepository,
            saFileRepository = saFileRepository,
            uploadEnqueuer = uploadEnqueuer,
            uploadQueueRepository = uploadQueueRepository,
            deletionQueueRepository = deletionQueueRepository,
            reviewProgressStore = reviewProgressStore,
            context = context,
            nativeEnhanceAdapter = nativeAdapter,
            settingsRepository = settingsRepository,
            savedStateHandle = SavedStateHandle(),
        )
    }

    private fun mockNativeEnhanceAdapter(resultFile: File) {
        val modelsTelemetry = EnhanceEngine.ModelsTelemetry(
            zeroDce = EnhanceEngine.ModelUsage(
                backend = EnhanceEngine.ModelBackend.NCNN,
                checksum = "abc123",
                expectedChecksum = "abc123",
                checksumOk = true,
            ),
            restormer = null,
        )

        every { nativeEnhanceAdapter.isReady() } returns true
        every { nativeEnhanceAdapter.modelsTelemetry() } returns modelsTelemetry
        coEvery { nativeEnhanceAdapter.initialize(any()) } returns Unit
        coEvery { nativeEnhanceAdapter.computePreview(any(), any(), any()) } coAnswers {
            val progress = thirdArg<(Float) -> Unit>()
            progress(0.25f)
            true
        }
        coEvery {
            nativeEnhanceAdapter.computeFull(any(), any(), any(), any(), any())
        } coAnswers {
            val output = thirdArg<File>()
            @Suppress("UNCHECKED_CAST")
            val onProgress = args[4] as (Float) -> Unit
            output.writeBytes(ByteArray(128) { 0x11 })
            onProgress(0.6f)
            com.kotopogoda.uploader.core.data.upload.UploadEnhancementInfo(
                strength = 0.5f,
                delegate = "cpu",
                metrics = com.kotopogoda.uploader.core.data.upload.UploadEnhancementMetrics(
                    lMean = 0.52f,
                    pDark = 0.28f,
                    bSharpness = 0.45f,
                    nNoise = 0.18f,
                ),
                fileSize = output.length(),
                previewTimingMs = 80L,
                fullTimingMs = 420L,
                usedVulkan = false,
                peakMemoryMb = 48f,
                cancelled = false,
            )
        }
    }
}
