package com.kotopogoda.uploader.feature.viewer

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.test.mock.MockContentResolver
import android.test.mock.MockContext
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import com.kotopogoda.uploader.core.data.photo.PhotoItem
import com.kotopogoda.uploader.core.data.photo.PhotoRepository
import com.kotopogoda.uploader.core.data.sa.SaFileRepository
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.uploadqueue.UploadQueueRepository
import com.kotopogoda.uploader.core.settings.ReviewProgressStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.eq
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.security.MessageDigest
import java.time.Instant
import kotlin.text.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import android.content.Intent
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

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
        coEvery { environment.saFileRepository.moveToProcessing(fileUri) } returns processingUri
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
        coEvery { environment.saFileRepository.moveToProcessing(fileUri) } returns processingUri
        coEvery { environment.saFileRepository.moveBack(any(), any(), any()) } returns fileUri

        val photo = PhotoItem(id = "2", uri = fileUri, takenAt = Instant.ofEpochMilli(2_000))
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
        val resolver = TestContentResolver { uri, _ ->
            if (uri == fileUri) {
                createSafCursor(documentId, displayName = "saf.jpg", size = 4096L, lastModified = 5555L)
            } else {
                null
            }
        }
        val context = TestContext(resolver)
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val idempotencySlot = slot<String>()
        val displayNameSlot = slot<String>()
        every {
            environment.uploadEnqueuer.enqueue(eq(fileUri), capture(idempotencySlot), capture(displayNameSlot))
        } just Runs

        val photo = PhotoItem(id = "3", uri = fileUri, takenAt = Instant.ofEpochMilli(3_000))
        environment.viewModel.updateVisiblePhoto(totalCount = 1, photo = photo)

        environment.viewModel.onEnqueueUpload(photo)
        advanceUntilIdle()

        verify(exactly = 1) {
            environment.uploadEnqueuer.enqueue(eq(fileUri), any(), any())
        }
        assertEquals("saf.jpg", displayNameSlot.captured)
        val expectedKey = buildExpectedIdempotencyKey(fileUri, size = 4096L, lastModified = 5555L)
        assertEquals(expectedKey, idempotencySlot.captured)
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
        val resolver = TestContentResolver { uri, _ ->
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
        }
        val context = TestContext(resolver)
        val environment = createEnvironment(context, folder)
        advanceUntilIdle()

        val idempotencySlot = slot<String>()
        val displayNameSlot = slot<String>()
        every {
            environment.uploadEnqueuer.enqueue(eq(fileUri), capture(idempotencySlot), capture(displayNameSlot))
        } just Runs

        val photo = PhotoItem(id = "4", uri = fileUri, takenAt = Instant.ofEpochMilli(4_000))
        environment.viewModel.updateVisiblePhoto(totalCount = 1, photo = photo)

        environment.viewModel.onEnqueueUpload(photo)
        advanceUntilIdle()

        verify(exactly = 1) {
            environment.uploadEnqueuer.enqueue(eq(fileUri), any(), any())
        }
        assertEquals("media.jpg", displayNameSlot.captured)
        val expectedKey = buildExpectedIdempotencyKey(fileUri, size = 8192L, lastModified = 20_000L)
        assertEquals(expectedKey, idempotencySlot.captured)
    }

    private fun createEnvironment(context: TestContext, folder: Folder): ViewModelEnvironment {
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
        coEvery { reviewProgressStore.loadPosition(any()) } returns null
        coEvery { reviewProgressStore.savePosition(any(), any(), any()) } just Runs
        every { uploadQueueRepository.observeQueue() } returns flowOf(emptyList())
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

        return ViewModelEnvironment(viewModel, saFileRepository, uploadEnqueuer)
    }

    private fun createSafCursor(
        documentId: String,
        displayName: String,
        size: Long,
        lastModified: Long
    ): Cursor {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID
        )
        return MatrixCursor(columns).apply {
            addRow(arrayOf(displayName, size, lastModified, documentId))
        }
    }

    private fun createMediaStoreCursor(
        displayName: String,
        size: Long,
        dateModifiedSeconds: Long,
        dateTaken: Long,
        relativePath: String
    ): Cursor {
        val columns = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.RELATIVE_PATH
        )
        return MatrixCursor(columns).apply {
            addRow(arrayOf(displayName, size, dateModifiedSeconds, dateTaken, relativePath))
        }
    }

    private fun buildExpectedIdempotencyKey(uri: Uri, size: Long?, lastModified: Long?): String {
        val base = if (size != null && size >= 0 && lastModified != null && lastModified > 0) {
            "${uri}|${size}|${lastModified}"
        } else {
            uri.toString()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(base.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class TestContext(
        private val resolver: TestContentResolver
    ) : MockContext() {
        override fun getContentResolver(): TestContentResolver = resolver
    }

    private class TestContentResolver(
        private val handler: (Uri, Array<out String>?) -> Cursor?
    ) : MockContentResolver() {
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor? = handler(uri, projection)
    }

    private data class ViewModelEnvironment(
        val viewModel: ViewerViewModel,
        val saFileRepository: SaFileRepository,
        val uploadEnqueuer: UploadEnqueuer
    )
}
