package com.kotopogoda.uploader.feature.viewer

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import android.test.mock.MockContentResolver
import android.test.mock.MockContext
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.data.sa.MoveResult
import com.kotopogoda.uploader.core.data.sa.SaFileRepository
import com.kotopogoda.uploader.core.data.upload.UploadEnqueueOptions
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.data.upload.idempotencyKeyFromContentSha256
import com.kotopogoda.uploader.core.data.util.Hashing
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.settings.ReviewPosition
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import com.kotopogoda.uploader.feature.viewer.R
import com.kotopogoda.uploader.feature.viewer.ViewerViewModel
import com.kotopogoda.uploader.feature.viewer.ViewerViewModel.EnhancementDelegateType
import com.kotopogoda.uploader.feature.viewer.ViewerViewModel.EnhancementResult
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.eq
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import kotlin.text.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import timber.log.Timber

class ViewerViewModelDocumentInfoTest {

    private val testScheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(testScheduler)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        ViewerViewModel.buildVersionOverride = null
    }

    @Test
    fun moveToProcessingWithSafUriUsesDocumentsContractParent() = runTest(context = dispatcher) {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda")
        val folder = Folder(
            id = 1,
            treeUri = treeUri.toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val documentId = "primary:Kotopogoda/Sub/saf.jpg"
        val fileUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AKotopogoda%2FSub%2Fsaf.jpg")
        val resolver = TestContentResolver { uri, _ ->
            if (uri == fileUri) {
                createSafCursor(documentId, displayName = "saf.jpg", size = 1024L, lastModified = 1234L)
            } else {
                null
            }
        }
        val context = TestContext(resolver)
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val processingUri = Uri.parse("content://processing/saf.jpg")
        coEvery { environment.saFileRepository.moveToProcessing(fileUri) } returns MoveResult.Success(processingUri)
        coEvery { environment.saFileRepository.moveBack(any(), any(), any()) } returns fileUri

        val photo = PhotoItem(id = "1", uri = fileUri, takenAt = Instant.ofEpochMilli(1_000))
        environment.viewModel.updateVisiblePhoto(totalCount = 1, photo = photo)

        environment.viewModel.onMoveToProcessing(photo)
        advanceUntilIdle()

        environment.viewModel.onUndo()
        advanceUntilIdle()

        val expectedParent = DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Kotopogoda/Sub")

        coVerify(exactly = 1) { environment.saFileRepository.moveToProcessing(fileUri) }
        coVerify(exactly = 1) {
            environment.saFileRepository.moveBack(
                processingUri,
                expectedParent,
                "saf.jpg"
            )
        }
    }

    @Test
    fun moveToProcessingWithMediaStoreUriUsesRelativePath() = runTest(context = dispatcher) {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda")
        val folder = Folder(
            id = 1,
            treeUri = treeUri.toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val fileUri = Uri.parse("content://media/external/images/media/42")
        val resolver = TestContentResolver { uri, _ ->
            if (uri == fileUri) {
                createMediaStoreCursor(
                    displayName = "media.jpg",
                    size = 2048L,
                    dateModifiedSeconds = 10L,
                    dateTaken = 0L,
                    relativePath = "Kotopogoda/Sub/"
                )
            } else {
                null
            }
        }
        val context = TestContext(resolver)
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val processingUri = Uri.parse("content://processing/media.jpg")
        coEvery { environment.saFileRepository.moveToProcessing(fileUri) } returns MoveResult.Success(processingUri)
        coEvery { environment.saFileRepository.moveBack(any(), any(), any()) } returns fileUri

        val photo = PhotoItem(id = "2", uri = fileUri, takenAt = Instant.ofEpochMilli(2_000))
        environment.viewModel.updateVisiblePhoto(totalCount = 1, photo = photo)

        ViewerViewModel.buildVersionOverride = Build.VERSION_CODES.R

        environment.viewModel.onMoveToProcessing(photo)
        advanceUntilIdle()

        environment.viewModel.onUndo()
        advanceUntilIdle()

        val expectedParent = DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Kotopogoda/Sub")

        coVerify(exactly = 1) { environment.saFileRepository.moveToProcessing(fileUri) }
        coVerify(exactly = 1) {
            environment.saFileRepository.moveBack(
                processingUri,
                expectedParent,
                "media.jpg"
            )
        }
    }
    @Test
    fun moveToProcessingWithRecoverableWriteShowsRequestAndCompletes() = runTest(context = dispatcher) {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda")
        val folder = Folder(
            id = 1,
            treeUri = treeUri.toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val fileUri = Uri.parse("content://media/external/images/media/43")
        val resolver = TestContentResolver { uri, _ ->
            if (uri == fileUri) {
                createMediaStoreCursor(
                    displayName = "media.jpg",
                    size = 2048L,
                    dateModifiedSeconds = 10L,
                    dateTaken = 0L,
                    relativePath = "Kotopogoda/Sub/"
                )
            } else {
                null
            }
        }
        val context = TestContext(resolver)
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val processingUri = Uri.parse("content://processing/media.jpg")
        val pendingIntent = mockk<PendingIntent>()
        val intentSender = mockk<IntentSender>()
        every { pendingIntent.intentSender } returns intentSender
        coEvery { environment.saFileRepository.moveToProcessing(fileUri) } returnsMany listOf(
            MoveResult.RequiresWritePermission(pendingIntent),
            MoveResult.Success(processingUri)
        )
        coEvery { environment.saFileRepository.moveBack(any(), any(), any()) } returns fileUri

        val photo = PhotoItem(id = "3", uri = fileUri, takenAt = Instant.ofEpochMilli(3_000))
        environment.viewModel.updateVisiblePhoto(totalCount = 1, photo = photo)

        ViewerViewModel.buildVersionOverride = Build.VERSION_CODES.R

        mockkStatic(Timber::class)
        val tree = mockk<Timber.Tree>(relaxed = true)
        every { Timber.tag("UI") } returns tree

        try {
            val requestEventDeferred = async {
                environment.viewModel.events.first { it is ViewerViewModel.ViewerEvent.RequestWrite }
            }

            environment.viewModel.onMoveToProcessing(photo)
            advanceUntilIdle()

            val requestEvent = requestEventDeferred.await()
            assertIs<ViewerViewModel.ViewerEvent.RequestWrite>(requestEvent)
            assertEquals(intentSender, requestEvent.intentSender)

            environment.viewModel.onWriteRequestResult(granted = true)
            advanceUntilIdle()

            environment.viewModel.onUndo()
            advanceUntilIdle()

            verify { tree.i("Batch move completed for %d photos", 1) }
        } finally {
            unmockkStatic(Timber::class)
        }

        val expectedParent = DocumentsContract.buildDocumentUriUsingTree(treeUri, "primary:Kotopogoda/Sub")

        coVerify(exactly = 2) { environment.saFileRepository.moveToProcessing(fileUri) }
        coVerify(exactly = 1) {
            environment.saFileRepository.moveBack(
                processingUri,
                expectedParent,
                "media.jpg"
            )
        }
    }


    @Test
    fun enqueueUploadWithSafUriUsesDocumentsContractMetadata() = runTest(context = dispatcher) {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda")
        val folder = Folder(
            id = 1,
            treeUri = treeUri.toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val documentId = "primary:Kotopogoda/saf.jpg"
        val fileUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AKotopogoda%2Fsaf.jpg")
        val fileBytes = "saf-content".toByteArray()
        val resolver = TestContentResolver(
            queryHandler = { uri, _ ->
                if (uri == fileUri) {
                    createSafCursor(documentId, displayName = "saf.jpg", size = 4096L, lastModified = 5555L)
                } else {
                    null
                }
            },
            inputStreamHandler = { uri ->
                if (uri == fileUri) ByteArrayInputStream(fileBytes) else null
            }
        )
        val context = TestContext(resolver)
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val idempotencySlot = slot<String>()
        val contentShaSlot = slot<String>()
        val displayNameSlot = slot<String>()
        val optionsSlot = slot<UploadEnqueueOptions>()
        coEvery {
            environment.uploadEnqueuer.enqueue(
                eq(fileUri),
                capture(idempotencySlot),
                capture(displayNameSlot),
                capture(contentShaSlot),
                capture(optionsSlot),
            )
        } just Runs

        val photo = PhotoItem(id = "3", uri = fileUri, takenAt = Instant.ofEpochMilli(3_000))
        environment.viewModel.updateVisiblePhoto(totalCount = 1, photo = photo)

        environment.viewModel.onEnqueueUpload(photo)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            environment.uploadEnqueuer.enqueue(eq(fileUri), any(), any(), any(), any())
        }
        assertEquals("saf.jpg", displayNameSlot.captured)
        val expectedKey = buildExpectedIdempotencyKey(fileBytes)
        assertEquals(expectedKey, idempotencySlot.captured)
        assertEquals(expectedDigest(fileBytes), contentShaSlot.captured)
    }

    @Test
    fun enqueueUploadWithMediaStoreUriUsesMediaStoreMetadata() = runTest(context = dispatcher) {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda")
        val folder = Folder(
            id = 1,
            treeUri = treeUri.toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val fileUri = Uri.parse("content://media/external/images/media/99")
        val fileBytes = "media-content".toByteArray()
        val resolver = TestContentResolver(
            queryHandler = { uri, _ ->
                if (uri == fileUri) {
                    createMediaStoreCursor(
                        displayName = "media.jpg",
                        size = 8192L,
                        dateModifiedSeconds = 20L,
                        dateTaken = 123_456L,
                        relativePath = "Kotopogoda/"
                    )
                } else {
                    null
                }
            },
            inputStreamHandler = { uri ->
                if (uri == fileUri) ByteArrayInputStream(fileBytes) else null
            }
        )
        val context = TestContext(resolver)
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val idempotencySlot = slot<String>()
        val contentShaSlot = slot<String>()
        val displayNameSlot = slot<String>()
        val optionsSlot = slot<UploadEnqueueOptions>()
        coEvery {
            environment.uploadEnqueuer.enqueue(
                eq(fileUri),
                capture(idempotencySlot),
                capture(displayNameSlot),
                capture(contentShaSlot),
                capture(optionsSlot),
            )
        } just Runs

        val photo = PhotoItem(id = "4", uri = fileUri, takenAt = Instant.ofEpochMilli(4_000))
        environment.viewModel.updateVisiblePhoto(totalCount = 1, photo = photo)

        environment.viewModel.onEnqueueUpload(photo)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            environment.uploadEnqueuer.enqueue(eq(fileUri), any(), any(), any(), any())
        }
        assertEquals("media.jpg", displayNameSlot.captured)
        val expectedKey = buildExpectedIdempotencyKey(fileBytes)
        assertEquals(expectedKey, idempotencySlot.captured)
        assertEquals(expectedDigest(fileBytes), contentShaSlot.captured)
    }

    @Test
    fun enqueueUploadUsesEnhancedResultWhenAvailable() = runTest(context = dispatcher) {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda")
        val folder = Folder(
            id = 1,
            treeUri = treeUri.toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val fileUri = Uri.parse("content://media/external/images/media/120")
        val enhancedFile = File.createTempFile("enhanced_", ".jpg").apply {
            writeText("enhanced-data")
        }
        val sourceFile = File.createTempFile("source_", ".jpg").apply {
            writeText("source-data")
        }
        val resolver = TestContentResolver(
            queryHandler = { uri, _ ->
                if (uri == fileUri) {
                    createMediaStoreCursor(
                        displayName = "media.jpg",
                        size = 8192L,
                        dateModifiedSeconds = 20L,
                        dateTaken = 123_456L,
                        relativePath = "Kotopogoda/"
                    )
                } else {
                    null
                }
            },
            inputStreamHandler = { uri ->
                when (uri) {
                    fileUri -> ByteArrayInputStream("original-data".toByteArray())
                    enhancedFile.toUri() -> ByteArrayInputStream("enhanced-data".toByteArray())
                    else -> null
                }
            }
        )
        val context = TestContext(resolver)
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val idempotencySlot = slot<String>()
        val contentShaSlot = slot<String>()
        val displayNameSlot = slot<String>()
        val optionsSlot = slot<UploadEnqueueOptions>()
        mockkObject(Hashing)
        try {
            every { Hashing.sha256(any(), eq(enhancedFile.toUri())) } returns "enhanced-digest"
            every { Hashing.sha256(any(), eq(fileUri)) } returns "original-digest"
            coEvery {
                environment.uploadEnqueuer.enqueue(
                    any(),
                    capture(idempotencySlot),
                    capture(displayNameSlot),
                    capture(contentShaSlot),
                    capture(optionsSlot),
                )
            } just Runs

            val photo = PhotoItem(id = "5", uri = fileUri, takenAt = Instant.ofEpochMilli(5_000))
            environment.viewModel.updateVisiblePhoto(totalCount = 1, photo = photo)

            val metrics = EnhanceEngine.Metrics(
                lMean = 0.45,
                pDark = 0.12,
                bSharpness = 0.82,
                nNoise = 0.08,
            )
            val enhancementResult = EnhancementResult(
                sourceFile = sourceFile,
                file = enhancedFile,
                uri = enhancedFile.toUri(),
                metrics = metrics,
                profile = EnhanceEngine.Profile(1f, 0f, 1f, 0f, 1f, 1f),
                delegate = EnhancementDelegateType.PRIMARY,
                engineDelegate = EnhanceEngine.Delegate.GPU,
                pipeline = EnhanceEngine.Pipeline(),
                timings = EnhanceEngine.Timings(),
            )
            val stateField = ViewerViewModel::class.java.getDeclaredField("_enhancementState")
            stateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val stateFlow = stateField.get(environment.viewModel) as kotlinx.coroutines.flow.MutableStateFlow<ViewerViewModel.EnhancementState>
            stateFlow.value = ViewerViewModel.EnhancementState(
                strength = 0.8f,
                inProgress = false,
                isResultReady = true,
                result = enhancementResult,
                resultUri = enhancementResult.uri,
                resultPhotoId = photo.id,
                isResultForCurrentPhoto = true,
            )

            environment.viewModel.onEnqueueUpload(photo)
            advanceUntilIdle()

            val expectedKey = idempotencyKeyFromContentSha256("enhanced-digest")
            assertEquals(expectedKey, idempotencySlot.captured)
            assertEquals("enhanced-digest", contentShaSlot.captured)
            assertEquals("media.jpg", displayNameSlot.captured)
            val capturedOptions = optionsSlot.captured
            assertEquals(photo.id, capturedOptions.photoId)
            assertTrue(capturedOptions.enhancement != null)
            assertEquals(0.8f, capturedOptions.enhancement?.strength)
            assertEquals("primary", capturedOptions.enhancement?.delegate)
            val metricsCaptured = capturedOptions.enhancement?.metrics
            assertEquals(0.45f, metricsCaptured?.lMean)
            assertEquals(0.12f, metricsCaptured?.pDark)
            assertEquals(0.82f, metricsCaptured?.bSharpness)
            assertEquals(0.08f, metricsCaptured?.nNoise)
            assertEquals(enhancedFile.length(), capturedOptions.enhancement?.fileSize)

            assertTrue(environment.viewModel.enhancementState.value.result == null)
        } finally {
            unmockkObject(Hashing)
        }
    }

    @Test
    fun restoresStoredIndexOnInitialization() = runTest(context = dispatcher) {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda")
        val folder = Folder(
            id = 1,
            treeUri = treeUri.toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val storedIndex = 3
        val storedPosition = ReviewPosition(index = storedIndex, anchorDate = Instant.ofEpochMilli(42))
        val context = TestContext(MockContentResolver())
        val environment = createEnvironment(context, folder, storedPosition)
        advanceUntilIdle()

        environment.viewModel.updateVisiblePhoto(totalCount = 10, photo = null)
        advanceUntilIdle()

        assertEquals(storedIndex, environment.viewModel.currentIndex.value)
    }

    @Test
    fun scrollToNewestResetsIndexAndSavesProgress() = runTest(context = dispatcher) {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda")
        val folder = Folder(
            id = 1,
            treeUri = treeUri.toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val storedPosition = ReviewPosition(index = 5, anchorDate = Instant.ofEpochMilli(10))
        val context = TestContext(MockContentResolver())
        val environment = createEnvironment(context, folder, storedPosition)
        advanceUntilIdle()

        val photo = PhotoItem(id = "1", uri = Uri.parse("content://photo/1"), takenAt = null)
        environment.viewModel.updateVisiblePhoto(totalCount = 10, photo = photo)
        environment.viewModel.onSkip(photo)
        advanceUntilIdle()
        environment.viewModel.onPhotoLongPress(photo)
        environment.viewModel.setCurrentIndex(3)
        advanceUntilIdle()
        assertTrue(environment.viewModel.undoCount.value > 0)
        assertTrue(environment.viewModel.selection.value.isNotEmpty())
        clearMocks(environment.reviewProgressStore, answers = false)

        environment.viewModel.scrollToNewest()
        advanceUntilIdle()

        assertEquals(0, environment.viewModel.currentIndex.value)
        assertTrue(environment.viewModel.selection.value.isEmpty())
        assertEquals(0, environment.viewModel.undoCount.value)
        coVerify(atLeast = 1) {
            environment.reviewProgressStore.savePosition(any(), 0, any())
        }
    }

    private fun createEnvironment(
        context: TestContext,
        folder: Folder,
        storedPosition: ReviewPosition? = null
    ): ViewModelEnvironment {
        val photoRepository = mockk<PhotoRepository>()
        val folderRepository = mockk<FolderRepository>()
        val saFileRepository = mockk<SaFileRepository>()
        val uploadEnqueuer = mockk<UploadEnqueuer>()
        val uploadQueueRepository = mockk<UploadQueueRepository>()
        val reviewProgressStore = mockk<ReviewProgressStore>()
        val savedStateHandle = SavedStateHandle()

        every { photoRepository.observePhotos() } returns flowOf(PagingData.empty())
        every { folderRepository.observeFolder() } returns flowOf(folder)
        coEvery { folderRepository.getFolder() } returns folder
        coEvery { reviewProgressStore.loadPosition(any()) } returns storedPosition
        coEvery { reviewProgressStore.savePosition(any(), any(), any()) } just Runs
        every { uploadQueueRepository.observeQueue() } returns flowOf(emptyList())
        every { uploadQueueRepository.observeQueuedOrProcessing(any()) } returns flowOf(false)
        every { uploadEnqueuer.isEnqueued(any()) } returns flowOf(false)

        val viewModel = ViewerViewModel(
            photoRepository = photoRepository,
            folderRepository = folderRepository,
            saFileRepository = saFileRepository,
            uploadEnqueuer = uploadEnqueuer,
            uploadQueueRepository = uploadQueueRepository,
            reviewProgressStore = reviewProgressStore,
            context = context,
            savedStateHandle = savedStateHandle
        )

        return ViewModelEnvironment(
            viewModel = viewModel,
            saFileRepository = saFileRepository,
            uploadEnqueuer = uploadEnqueuer,
            uploadQueueRepository = uploadQueueRepository,
            reviewProgressStore = reviewProgressStore
        )
    }

    @Test
    fun observeUploadEnqueuedReflectsQueueState() = runTest(context = dispatcher) {
        val folder = Folder(
            id = 1,
            treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda").toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val context = TestContext(MockContentResolver())
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val photo = PhotoItem(id = "queued", uri = Uri.parse("content://photo/queued"), takenAt = Instant.ofEpochMilli(0))
        every { environment.uploadEnqueuer.isEnqueued(photo.uri) } returns flowOf(false)
        every { environment.uploadQueueRepository.observeQueuedOrProcessing(photo.id) } returns flowOf(true)

        val result = environment.viewModel.observeUploadEnqueued(photo).first()

        assertTrue(result)
    }

    @Test
    fun selectionStateUpdatesOnToggleAndClear() = runTest(context = dispatcher) {
        val folder = Folder(
            id = 1,
            treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda").toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val context = TestContext(MockContentResolver())
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val first = PhotoItem(id = "1", uri = Uri.parse("content://photo/1"), takenAt = null)
        val second = PhotoItem(id = "2", uri = Uri.parse("content://photo/2"), takenAt = null)

        environment.viewModel.onPhotoLongPress(first)
        assertEquals(setOf(first), environment.viewModel.selection.value)
        assertTrue(environment.viewModel.isSelectionMode.value)

        environment.viewModel.onToggleSelection(second)
        assertEquals(setOf(first, second), environment.viewModel.selection.value)

        environment.viewModel.onToggleSelection(first)
        assertEquals(setOf(second), environment.viewModel.selection.value)

        environment.viewModel.clearSelection()
        assertTrue(environment.viewModel.selection.value.isEmpty())
        assertTrue(!environment.viewModel.isSelectionMode.value)
    }

    @Test
    fun moveSelectionClearsSelectionAfterFinalize() = runTest(context = dispatcher) {
        val folder = Folder(
            id = 1,
            treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda").toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val context = TestContext(MockContentResolver())
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val first = PhotoItem(
            id = "media-1",
            uri = Uri.parse("content://media/external/images/media/1"),
            takenAt = Instant.ofEpochMilli(0)
        )
        val second = PhotoItem(
            id = "media-2",
            uri = Uri.parse("content://media/external/images/media/2"),
            takenAt = Instant.ofEpochMilli(1)
        )

        environment.viewModel.onPhotoLongPress(first)
        environment.viewModel.onToggleSelection(second)

        coEvery { environment.saFileRepository.moveToProcessing(first.uri) } returns MoveResult.Success(Uri.parse("content://processing/1"))
        coEvery { environment.saFileRepository.moveToProcessing(second.uri) } returns MoveResult.Success(Uri.parse("content://processing/2"))

        ViewerViewModel.buildVersionOverride = Build.VERSION_CODES.Q

        environment.viewModel.onMoveSelection()
        advanceUntilIdle()

        assertTrue(environment.viewModel.selection.value.isEmpty())
        assertTrue(!environment.viewModel.isSelectionMode.value)
        coVerify(exactly = 1) { environment.saFileRepository.moveToProcessing(first.uri) }
        coVerify(exactly = 1) { environment.saFileRepository.moveToProcessing(second.uri) }
    }

    @Test
    fun moveToProcessingEmitsToastEvent() = runTest(context = dispatcher) {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AKotopogoda")
        val folder = Folder(
            id = 1,
            treeUri = treeUri.toString(),
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        val documentId = "primary:Kotopogoda/Sub/saf.jpg"
        val fileUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AKotopogoda%2FSub%2Fsaf.jpg")
        val resolver = TestContentResolver { uri, _ ->
            if (uri == fileUri) {
                createSafCursor(documentId, displayName = "saf.jpg", size = 1024L, lastModified = 1234L)
            } else {
                null
            }
        }
        val context = TestContext(resolver)
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val processingUri = Uri.parse("content://processing/saf.jpg")
        coEvery { environment.saFileRepository.moveToProcessing(fileUri) } returns MoveResult.Success(processingUri)

        val photo = PhotoItem(id = "1", uri = fileUri, takenAt = Instant.ofEpochMilli(1_000))
        environment.viewModel.updateVisiblePhoto(totalCount = 2, photo = photo)

        val eventDeferred = async {
            environment.viewModel.events.first { it is ViewerViewModel.ViewerEvent.ShowToast }
        }

        environment.viewModel.onMoveToProcessing(photo)
        advanceUntilIdle()

        val event = eventDeferred.await() as ViewerViewModel.ViewerEvent.ShowToast
        assertEquals(R.string.viewer_toast_processing_success, event.messageRes)
    }

    private fun createSafCursor(
        documentId: String,
        displayName: String,
        size: Long,
        lastModified: Long,
        mimeType: String = "image/jpeg"
    ): Cursor {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID
        )
        return MatrixCursor(columns).apply {
            addRow(arrayOf(displayName, size, lastModified, mimeType, documentId))
        }
    }

    private fun createMediaStoreCursor(
        displayName: String,
        size: Long,
        dateModifiedSeconds: Long,
        dateTaken: Long,
        relativePath: String,
        mimeType: String = "image/jpeg"
    ): Cursor {
        val columns = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE
        )
        return MatrixCursor(columns).apply {
            addRow(arrayOf(displayName, size, dateModifiedSeconds, dateTaken, relativePath, mimeType))
        }
    }

    private fun buildExpectedIdempotencyKey(content: ByteArray): String {
        return "upload:${expectedDigest(content)}"
    }

    private fun expectedDigest(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class TestContext(
        private val resolver: TestContentResolver
    ) : MockContext() {
        override fun getContentResolver(): TestContentResolver = resolver
    }

    private class TestContentResolver(
        private val handler: (Uri, Array<out String>?) -> Cursor?,
        private val inputStreamHandler: (Uri) -> InputStream? = { null }
    ) : MockContentResolver() {
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = handler(uri, projection)

        override fun openInputStream(uri: Uri): InputStream? = inputStreamHandler(uri)
    }

    private data class ViewModelEnvironment(
        val viewModel: ViewerViewModel,
        val saFileRepository: SaFileRepository,
        val uploadEnqueuer: UploadEnqueuer,
        val uploadQueueRepository: UploadQueueRepository,
        val reviewProgressStore: ReviewProgressStore
    )
}
