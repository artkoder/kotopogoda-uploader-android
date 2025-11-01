package com.kotopogoda.uploader.feature.viewer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.data.sa.SaFileRepository
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import com.kotopogoda.uploader.core.settings.SettingsRepository
import com.kotopogoda.uploader.core.settings.AppSettings
import com.kotopogoda.uploader.core.settings.PreviewQuality
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceAdapter
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewerViewModelEnhanceLogTest {

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

    @BeforeTest
    fun setUp() {
        MockKAnnotations.init(this, relaxUnitFun = true)
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
        runCatching { unmockkObject(UploadLog) }
    }

    @Test
    fun `seam metrics propagated to enhance logs`() {
        val capturedDetails = mutableListOf<Array<out Pair<String, Any?>>>()
        mockkObject(UploadLog)
        every {
            UploadLog.message(any(), any(), any(), any(), any(), capture(capturedDetails))
        } returns "log"

        val viewModel = ViewerViewModel(
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

        val photo = PhotoItem(id = "1", uri = Uri.parse("content://photo/1"), takenAt = Instant.now())
        val sourceFile = File.createTempFile("source", ".jpg")
        val resultFile = File.createTempFile("result", ".jpg")
        val pipeline = EnhanceEngine.Pipeline(
            stages = listOf("restormer"),
            tileSize = 512,
            overlap = 64,
            tileSizeActual = 480,
            overlapActual = 48,
            mixingWindow = 96,
            tileCount = 4,
            tilesCompleted = 4,
            tileProgress = 1f,
            tileUsed = true,
            restormerApplied = true,
            zeroDceDelegateFallback = true,
            restormerDelegateFallback = false,
            seamMaxDelta = 0.42f,
            seamMeanDelta = 0.21f,
            seamArea = 12,
            seamZeroArea = 2,
            seamMinWeight = 0.15f,
            seamMaxWeight = 1.75f,
        )
        val enhancementResult = ViewerViewModel.EnhancementResult(
            sourceFile = sourceFile,
            file = resultFile,
            uri = Uri.parse(resultFile.toURI().toString()),
            metrics = EnhanceEngine.Metrics(0.4, 0.2, 0.6, 0.1),
            profile = EnhanceEngine.Profile(
                isLowLight = true,
                kDce = 0.5f,
                restormerMix = 0.7f,
                alphaDetail = 0.4f,
                sharpenAmount = 0.3f,
                sharpenRadius = 1.2f,
                sharpenThreshold = 0.05f,
                vibranceGain = 0.2f,
                saturationGain = 1.1f,
            ),
            delegate = ViewerViewModel.EnhancementDelegateType.PRIMARY,
            engineDelegate = EnhanceEngine.Delegate.GPU,
            pipeline = pipeline,
            timings = EnhanceEngine.Timings(),
            models = EnhanceEngine.ModelsTelemetry(
                zeroDce = EnhanceEngine.ModelUsage(
                    backend = EnhanceEngine.ModelBackend.TFLITE,
                    checksum = "aaa",
                    expectedChecksum = "aaa",
                    checksumOk = true,
                ),
                restormer = EnhanceEngine.ModelUsage(
                    backend = EnhanceEngine.ModelBackend.TFLITE,
                    checksum = "bbb",
                    expectedChecksum = "ccc",
                    checksumOk = false,
                ),
            ),
        )

        val method = ViewerViewModel::class.java.getDeclaredMethod(
            "logEnhancementResult",
            PhotoItem::class.java,
            ViewerViewModel.EnhancementResult::class.java,
            Float::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(viewModel, photo, enhancementResult, 0.6f)

        val loggedDetails = capturedDetails.lastOrNull().orEmpty().associate { it }
        assertEquals("0.420", loggedDetails["seam_max_delta"])
        assertEquals("0.210", loggedDetails["seam_mean_delta"])
        assertEquals("12", loggedDetails["seam_area"])
        assertEquals("2", loggedDetails["seam_zero_area"])
        assertEquals("0.150", loggedDetails["seam_min_weight"])
        assertEquals("1.750", loggedDetails["seam_max_weight"])
        assertEquals("true", loggedDetails["tile_used"])
        assertEquals("480", loggedDetails["tile_size_actual"])
        assertEquals("48", loggedDetails["tile_overlap_actual"])
        assertEquals("96", loggedDetails["mixing_window"])
        assertEquals("96", loggedDetails["mixing_window_actual"])
        assertEquals("4", loggedDetails["tiles_completed"])
        assertEquals("1.000", loggedDetails["tile_progress"])
        assertEquals("true", loggedDetails["zero_dce_delegate_fallback"])
        assertEquals("false", loggedDetails["restormer_delegate_fallback"])
        assertEquals("true", loggedDetails["sha256_ok_zero_dce"])
        assertEquals("false", loggedDetails["sha256_ok_restormer"])

        sourceFile.delete()
        resultFile.delete()
    }
}
