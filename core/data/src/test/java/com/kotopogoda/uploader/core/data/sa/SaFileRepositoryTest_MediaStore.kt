package com.kotopogoda.uploader.core.data.sa

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.app.RemoteAction
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.database.MatrixCursor
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import io.mockk.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

/**
 * Тесты для операций с MediaStore документами
 * Разделено на отдельный класс для снижения потребления памяти при mockkинге
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SaFileRepositoryTest_MediaStore {

    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val processingFolderProvider = mockk<ProcessingFolderProvider>(relaxed = true)
    private val repository = SaFileRepository(context, processingFolderProvider)
    private val originalMediaStoreVolumeResolver = mediaStoreVolumeResolver
    private val originalMediaStoreWriteRequestFactory = mediaStoreWriteRequestFactory
    private val originalMediaStoreDeleteRequestFactory = mediaStoreDeleteRequestFactory

    init {
        every { context.contentResolver } returns contentResolver
    }

    @Before
    fun setUp() {
        mockkStatic(DocumentFile::class)
        mockkStatic(DocumentsContract::class)
        mockkStatic(MediaStore::class)
    }

    @After
    fun tearDown() {
        mediaStoreVolumeResolver = originalMediaStoreVolumeResolver
        mediaStoreWriteRequestFactory = originalMediaStoreWriteRequestFactory
        mediaStoreDeleteRequestFactory = originalMediaStoreDeleteRequestFactory
        clearAllMocks(answers = false)
    }

    @Test
    fun `moveToProcessing updates relative path for MediaStore document on same volume`() = runTest {
        val mediaUri = Uri.parse("content://media/external/images/media/42")
        val destinationFolderUri = Uri.parse(
            "content://com.android.providers.media.documents/tree/external%3Apictures/document/external%3Apictures%2FНа%20обработку"
        )
        val destinationFolder = mockk<DocumentFile>(relaxed = true)
        val valuesSlot = slot<ContentValues>()

        mediaStoreVolumeResolver = { MediaStore.VOLUME_EXTERNAL_PRIMARY }

        every { destinationFolder.uri } returns destinationFolderUri
        coEvery { processingFolderProvider.ensure() } returns destinationFolder

        every { DocumentsContract.getDocumentId(destinationFolderUri) } returns "external:Pictures/На обработку"

        every { contentResolver.update(eq(mediaUri), capture(valuesSlot), any(), any()) } returns 1

        val result = repository.moveToProcessing(mediaUri)

        val success = assertIs<MoveResult.Success>(result)
        assertEquals(mediaUri, success.uri)
        assertEquals("Pictures/На обработку/", valuesSlot.captured.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        verify(exactly = 1) { contentResolver.update(eq(mediaUri), any(), any(), any()) }
        verify(exactly = 0) { contentResolver.getType(any()) }
        verify(exactly = 0) { contentResolver.openInputStream(any()) }
        verify(exactly = 0) { contentResolver.openOutputStream(any()) }
    }

    @Test
    fun `moveToProcessing returns write confirmation when update throws RecoverableSecurityException`() = runTest {
        val mediaUri = Uri.parse("content://media/external/images/media/99")
        val destinationFolderUri = Uri.parse(
            "content://com.android.providers.media.documents/tree/external%3Apictures/document/external%3Apictures%2FНа обработку"
        )
        val destinationFolder = mockk<DocumentFile>(relaxed = true)
        val pendingIntent = mockk<PendingIntent>(relaxed = true)
        val intentSender = mockk<IntentSender>()
        val icon = mockk<Icon>()
        val remoteAction = RemoteAction(icon, "Write Permission", "Need write permission", pendingIntent)

        mediaStoreVolumeResolver = { MediaStore.VOLUME_EXTERNAL_PRIMARY }

        every { destinationFolder.uri } returns destinationFolderUri
        coEvery { processingFolderProvider.ensure() } returns destinationFolder

        every { MediaStore.createWriteRequest(contentResolver, listOf(mediaUri)) } returns pendingIntent
        every { pendingIntent.intentSender } returns intentSender

        every { DocumentsContract.getDocumentId(destinationFolderUri) } returns "external:Pictures/На обработку"

        every {
            contentResolver.update(eq(mediaUri), any(), any(), any())
        } throws RecoverableSecurityException(
            SecurityException("no access"),
            "Need write permission",
            remoteAction
        )

        val result = repository.moveToProcessing(mediaUri)

        val confirmation = assertIs<MoveResult.RequiresWritePermission>(result)
        assertEquals(pendingIntent, confirmation.pendingIntent)
        verify(exactly = 1) { MediaStore.createWriteRequest(contentResolver, listOf(mediaUri)) }
        verify(exactly = 1) { contentResolver.update(eq(mediaUri), any(), any(), any()) }
        verify(exactly = 0) { contentResolver.getType(any()) }
        verify(exactly = 0) { contentResolver.openInputStream(any()) }
        verify(exactly = 0) { contentResolver.openOutputStream(any()) }
    }

    @Test
    fun `moveToProcessing updates MediaStore document within same volume`() = runTest {
        val mediaUri = Uri.parse("content://media/external_primary/images/media/777")
        val destinationFolderUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Kotopogoda/Processing")
        val expectedDocumentId = "primary:Kotopogoda/Processing/bar.jpg"
        val expectedProcessingUri = Uri.parse("content://com.android.externalstorage.documents/document/$expectedDocumentId")
        val destinationFolder = mockk<DocumentFile>(relaxed = true)

        mediaStoreVolumeResolver = { MediaStore.VOLUME_EXTERNAL_PRIMARY }

        every { destinationFolder.uri } returns destinationFolderUri
        every { DocumentsContract.getDocumentId(destinationFolderUri) } returns "primary:Kotopogoda/Processing"
        every { DocumentsContract.buildDocumentUriUsingTree(destinationFolderUri, expectedDocumentId) } returns expectedProcessingUri

        val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf<Any>("bar.jpg"))
        }

        coEvery { processingFolderProvider.ensure() } returns destinationFolder
        every { destinationFolder.listFiles() } returns emptyArray()

        every { contentResolver.getType(mediaUri) } returns "image/jpeg"
        every { contentResolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null) } returns cursor
        every { contentResolver.update(eq(mediaUri), any(), isNull(), isNull()) } returns 1

        val result = repository.moveToProcessing(mediaUri)

        val success = assertIs<MoveResult.Success>(result)
        assertEquals(expectedProcessingUri, success.uri)
        verify(exactly = 1) {
            contentResolver.update(
                eq(mediaUri),
                match { values ->
                    values.get(MediaStore.MediaColumns.RELATIVE_PATH) == "Kotopogoda/Processing/" &&
                        values.get(MediaStore.MediaColumns.DISPLAY_NAME) == null
                },
                null,
                null
            )
        }
        verify(exactly = 0) { contentResolver.openInputStream(any()) }
        verify(exactly = 0) { contentResolver.openOutputStream(any()) }
    }
}
