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
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import io.mockk.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SaFileRepositoryTest {

    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val processingFolderProvider = mockk<ProcessingFolderProvider>()
    private val repository = SaFileRepository(context, processingFolderProvider)

    init {
        every { context.contentResolver } returns contentResolver
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `moveToProcessing copies and deletes SAF document`() = runTest {
        val safUri = Uri.parse("content://com.android.providers.documents/document/primary:Pictures/foo.jpg")
        val destinationUri = Uri.parse("content://com.example.destination/document/1")
        val sourceDocument = mockk<DocumentFile>(relaxed = true)
        val destinationFolder = mockk<DocumentFile>(relaxed = true)
        val destinationDocument = mockk<DocumentFile>(relaxed = true)
        val inputBytes = "hello-world".toByteArray()
        val outputStream = ByteArrayOutputStream()

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromSingleUri(context, safUri) } returns sourceDocument

        every { sourceDocument.type } returns "image/jpeg"
        every { sourceDocument.name } returns "foo.jpg"
        every { sourceDocument.uri } returns safUri
        every { sourceDocument.delete() } returns true

        coEvery { processingFolderProvider.ensure() } returns destinationFolder
        every { destinationFolder.listFiles() } returns emptyArray()
        every { destinationFolder.createFile("image/jpeg", "foo.jpg") } returns destinationDocument
        every { destinationDocument.uri } returns destinationUri

        every { contentResolver.openInputStream(safUri) } returns ByteArrayInputStream(inputBytes)
        every { contentResolver.openOutputStream(destinationUri) } returns outputStream

        val result = repository.moveToProcessing(safUri)

        val success = assertIs<MoveResult.Success>(result)
        assertEquals(destinationUri, success.uri)
        assertContentEquals(inputBytes, outputStream.toByteArray())
        verify(exactly = 1) { sourceDocument.delete() }
    }

    @Test
    fun `moveToProcessing updates relative path for MediaStore document on same volume`() = runTest {
        val mediaUri = Uri.parse("content://media/external/images/media/42")
        val destinationFolderUri = Uri.parse(
            "content://com.android.providers.media.documents/tree/external%3Apictures/document/external%3Apictures%2FНа%20обработку"
        )
        val destinationFolder = mockk<DocumentFile>(relaxed = true)
        val valuesSlot = slot<ContentValues>()

        every { destinationFolder.uri } returns destinationFolderUri
        coEvery { processingFolderProvider.ensure() } returns destinationFolder

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getDocumentId(destinationFolderUri) } returns "external:Pictures/На обработку"

        every { contentResolver.update(eq(mediaUri), capture(valuesSlot), any(), any()) } returns 1

        val result = repository.moveToProcessing(mediaUri)

        val success = assertIs<MoveResult.Success>(result)
        assertEquals(mediaUri, success.uri)
        assertEquals("Pictures/На обработку/", valuesSlot.captured.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        verify(exactly = 0) { MediaStore.createWriteRequest(any(), any()) }
        verify(exactly = 1) { contentResolver.update(eq(mediaUri), any(), any(), any()) }
        verify(exactly = 0) { MediaStore.createDeleteRequest(any(), any()) }
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

        every { destinationFolder.uri } returns destinationFolderUri
        coEvery { processingFolderProvider.ensure() } returns destinationFolder


        every { MediaStore.createWriteRequest(contentResolver, listOf(mediaUri)) } returns pendingIntent
        every { pendingIntent.intentSender } returns intentSender

        mockkStatic(DocumentsContract::class)
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
    fun `moveToProcessing copies and requests deletion for MediaStore document on different volume`() = runTest {
        val mediaUri = Uri.parse("content://media/external/images/media/42")
        val destinationUri = Uri.parse("content://com.example.destination/document/2")
        val destinationFolder = mockk<DocumentFile>(relaxed = true)
        val destinationDocument = mockk<DocumentFile>(relaxed = true)
        val pendingIntent = mockk<PendingIntent>(relaxed = true)
        val intentSender = mockk<IntentSender>()
        val icon = mockk<Icon>()
        val remoteAction = RemoteAction(icon, "Delete Permission", "Need delete permission", pendingIntent)
        val inputBytes = "media-bytes".toByteArray()
        val outputStream = ByteArrayOutputStream()

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromSingleUri(context, any()) } throws AssertionError("Should not access DocumentFile for MediaStore URIs")


        every { MediaStore.getVolumeName(mediaUri) } returns MediaStore.VOLUME_EXTERNAL_PRIMARY
        every { MediaStore.createDeleteRequest(contentResolver, listOf(mediaUri)) } returns pendingIntent
        every { pendingIntent.intentSender } returns intentSender

        mockkStatic(DocumentsContract::class)
        val destinationTreeUri = Uri.parse("content://com.android.externalstorage.documents/document/1234-5678:Kotopogoda/Processing")
        every { destinationFolder.uri } returns destinationTreeUri
        every { DocumentsContract.getDocumentId(destinationTreeUri) } returns "1234-5678:Kotopogoda/Processing"

        val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf<Any>("bar.jpg"))
        }

        coEvery { processingFolderProvider.ensure() } returns destinationFolder
        every { destinationFolder.listFiles() } returns emptyArray()
        every { destinationFolder.createFile("image/jpeg", "bar.jpg") } returns destinationDocument
        every { destinationDocument.uri } returns destinationUri

        every { contentResolver.getType(mediaUri) } returns "image/jpeg"
        every { contentResolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null) } returns cursor
        every { contentResolver.openInputStream(mediaUri) } returns ByteArrayInputStream(inputBytes)
        every { contentResolver.openOutputStream(destinationUri) } returns outputStream
        every { contentResolver.delete(mediaUri, null, null) } throws RecoverableSecurityException(
            SecurityException("no access"),
            "Need delete permission",
            remoteAction
        )

        val result = repository.moveToProcessing(mediaUri)

        val permission = assertIs<MoveResult.RequiresDeletePermission>(result)
        assertEquals(pendingIntent, permission.pendingIntent)
        assertContentEquals(inputBytes, outputStream.toByteArray())
        verify(exactly = 0) { MediaStore.createWriteRequest(any(), any()) }
        verify(exactly = 1) { MediaStore.createDeleteRequest(contentResolver, listOf(mediaUri)) }
        verify(exactly = 1) { contentResolver.delete(mediaUri, null, null) }
        verify(exactly = 0) { MediaStore.createWriteRequest(any(), any()) }
        verify(exactly = 0) { contentResolver.update(any(), any(), any(), any()) }
    }
    @Test
    fun `moveToProcessing updates MediaStore document within same volume`() = runTest {
        val mediaUri = Uri.parse("content://media/external_primary/images/media/777")
        val destinationFolderUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Kotopogoda/Processing")
        val expectedDocumentId = "primary:Kotopogoda/Processing/bar.jpg"
        val expectedProcessingUri = Uri.parse("content://com.android.externalstorage.documents/document/$expectedDocumentId")
        val destinationFolder = mockk<DocumentFile>(relaxed = true)


        every { MediaStore.getVolumeName(mediaUri) } returns MediaStore.VOLUME_EXTERNAL_PRIMARY

        mockkStatic(DocumentsContract::class)
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
        verify(exactly = 0) { MediaStore.createWriteRequest(any(), any()) }
        verify(exactly = 0) { contentResolver.openInputStream(any()) }
        verify(exactly = 0) { contentResolver.openOutputStream(any()) }
        verify(exactly = 0) { MediaStore.createDeleteRequest(any(), any()) }
    }

    @Test
    fun `moveToProcessing generates unique name when SAF destination has duplicate`() = runTest {
        val safUri = Uri.parse("content://com.android.providers.documents/document/primary:Pictures/foo.jpg")
        val destinationUri = Uri.parse("content://com.example.destination/document/unique")
        val sourceDocument = mockk<DocumentFile>(relaxed = true)
        val destinationFolder = mockk<DocumentFile>(relaxed = true)
        val destinationDocument = mockk<DocumentFile>(relaxed = true)
        val existingDocument = mockk<DocumentFile>(relaxed = true)
        val inputBytes = "duplicate".toByteArray()
        val outputStream = ByteArrayOutputStream()

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromSingleUri(context, safUri) } returns sourceDocument

        every { sourceDocument.type } returns "image/jpeg"
        every { sourceDocument.name } returns "foo.jpg"
        every { sourceDocument.uri } returns safUri
        every { sourceDocument.delete() } returns true

        coEvery { processingFolderProvider.ensure() } returns destinationFolder
        every { existingDocument.isFile } returns true
        every { existingDocument.name } returns "foo.jpg"
        every { destinationFolder.listFiles() } returns arrayOf(existingDocument)
        every { destinationFolder.createFile("image/jpeg", "foo-1.jpg") } returns destinationDocument
        every { destinationDocument.uri } returns destinationUri

        every { contentResolver.openInputStream(safUri) } returns ByteArrayInputStream(inputBytes)
        every { contentResolver.openOutputStream(destinationUri) } returns outputStream

        val result = repository.moveToProcessing(safUri)

        val success = assertIs<MoveResult.Success>(result)
        assertEquals(destinationUri, success.uri)
        assertContentEquals(inputBytes, outputStream.toByteArray())
        verify(exactly = 1) { destinationFolder.createFile("image/jpeg", "foo-1.jpg") }
        verify(exactly = 0) { destinationFolder.createFile("image/jpeg", "foo.jpg") }
    }

    @Test
    fun `moveToProcessing treats duplicate extensions case-insensitively`() = runTest {
        val safUri = Uri.parse("content://com.android.providers.documents/document/primary:Pictures/foo.jpg")
        val destinationUri = Uri.parse("content://com.example.destination/document/unique-upper")
        val sourceDocument = mockk<DocumentFile>(relaxed = true)
        val destinationFolder = mockk<DocumentFile>(relaxed = true)
        val destinationDocument = mockk<DocumentFile>(relaxed = true)
        val existingDocument = mockk<DocumentFile>(relaxed = true)
        val inputBytes = "duplicate-upper".toByteArray()
        val outputStream = ByteArrayOutputStream()

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromSingleUri(context, safUri) } returns sourceDocument

        every { sourceDocument.type } returns "image/jpeg"
        every { sourceDocument.name } returns "foo.jpg"
        every { sourceDocument.uri } returns safUri
        every { sourceDocument.delete() } returns true

        coEvery { processingFolderProvider.ensure() } returns destinationFolder
        every { existingDocument.isFile } returns true
        every { existingDocument.name } returns "foo.JPG"
        every { destinationFolder.listFiles() } returns arrayOf(existingDocument)
        every { destinationFolder.createFile("image/jpeg", "foo-1.jpg") } returns destinationDocument
        every { destinationDocument.uri } returns destinationUri

        every { contentResolver.openInputStream(safUri) } returns ByteArrayInputStream(inputBytes)
        every { contentResolver.openOutputStream(destinationUri) } returns outputStream

        val result = repository.moveToProcessing(safUri)

        val success = assertIs<MoveResult.Success>(result)
        assertEquals(destinationUri, success.uri)
        assertContentEquals(inputBytes, outputStream.toByteArray())
        verify(exactly = 1) { destinationFolder.createFile("image/jpeg", "foo-1.jpg") }
    }

    @Test
    fun `moveToProcessing generates unique name when MediaStore destination has duplicate`() = runTest {
        val mediaUri = Uri.parse("content://media/external/images/media/42")
        val destinationUri = Uri.parse("content://com.example.destination/document/unique-media")
        val destinationFolderUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AKotopogoda/document/primary%3AKotopogoda%2FНа обработку"
        )
        val destinationFolder = mockk<DocumentFile>(relaxed = true)
        val destinationDocument = mockk<DocumentFile>(relaxed = true)
        val existingDocument = mockk<DocumentFile>(relaxed = true)
        val pendingIntent = mockk<PendingIntent>(relaxed = true)
        val intentSender = mockk<IntentSender>()
        val icon = mockk<Icon>()
        val remoteAction = RemoteAction(icon, "Delete Permission", "Need delete permission", pendingIntent)
        val inputBytes = "media-duplicate".toByteArray()
        val outputStream = ByteArrayOutputStream()

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromSingleUri(context, any()) } throws AssertionError("Should not access DocumentFile for MediaStore URIs")

        mockkStatic(MediaStore::class)
        every { MediaStore.getVolumeName(mediaUri) } returns MediaStore.VOLUME_EXTERNAL_PRIMARY
        every { MediaStore.createDeleteRequest(contentResolver, listOf(mediaUri)) } returns pendingIntent
        every { pendingIntent.intentSender } returns intentSender

        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getDocumentId(destinationFolderUri) } returns "primary:Kotopogoda/На обработку"

        val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf<Any>("bar.jpg"))
        }

        coEvery { processingFolderProvider.ensure() } returns destinationFolder
        every { destinationFolder.uri } returns destinationFolderUri
        every { existingDocument.isFile } returns true
        every { existingDocument.name } returns "bar.jpg"
        every { destinationFolder.listFiles() } returns arrayOf(existingDocument)
        every { destinationFolder.createFile("image/jpeg", "bar-1.jpg") } returns destinationDocument
        every { destinationDocument.uri } returns destinationUri

        every { contentResolver.getType(mediaUri) } returns "image/jpeg"
        every { contentResolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null) } returns cursor
        every { contentResolver.openInputStream(mediaUri) } returns ByteArrayInputStream(inputBytes)
        every { contentResolver.openOutputStream(destinationUri) } returns outputStream
        every { contentResolver.delete(mediaUri, null, null) } throws RecoverableSecurityException(
            SecurityException("no access"),
            "Need delete permission",
            remoteAction
        )

        val result = repository.moveToProcessing(mediaUri)

        val permission = assertIs<MoveResult.RequiresDeletePermission>(result)
        assertEquals(pendingIntent, permission.pendingIntent)
        assertContentEquals(inputBytes, outputStream.toByteArray())
        verify(exactly = 1) { destinationFolder.createFile("image/jpeg", "bar-1.jpg") }
        verify(exactly = 0) { destinationFolder.createFile("image/jpeg", "bar.jpg") }
        verify(exactly = 1) { MediaStore.createDeleteRequest(contentResolver, listOf(mediaUri)) }
        verify(exactly = 1) { contentResolver.delete(mediaUri, null, null) }
        verify(exactly = 0) { MediaStore.createWriteRequest(any(), any()) }
    }
    @Test
    fun `moveBack recreates file with unique name when duplicate exists`() = runTest {
        val processingUri = Uri.parse("content://com.example.processing/document/processing")
        val originalParentUri = Uri.parse("content://com.example.destination/tree/original")
        val destinationUri = Uri.parse("content://com.example.destination/document/restored")
        val sourceDocument = mockk<DocumentFile>(relaxed = true)
        val parentDocument = mockk<DocumentFile>(relaxed = true)
        val destinationDocument = mockk<DocumentFile>(relaxed = true)
        val existingDocument = mockk<DocumentFile>(relaxed = true)
        val inputBytes = "restore".toByteArray()
        val outputStream = ByteArrayOutputStream()

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromSingleUri(context, processingUri) } returns sourceDocument
        every { DocumentFile.fromTreeUri(context, originalParentUri) } returns parentDocument

        every { sourceDocument.type } returns "image/jpeg"
        every { sourceDocument.delete() } returns true
        every { destinationDocument.uri } returns destinationUri

        every { existingDocument.name } returns "bar.jpg"
        every { existingDocument.isFile } returns true
        every { parentDocument.listFiles() } returns arrayOf(existingDocument)
        every { parentDocument.createFile("image/jpeg", "bar-1.jpg") } returns destinationDocument

        every { contentResolver.openInputStream(processingUri) } returns ByteArrayInputStream(inputBytes)
        every { contentResolver.openOutputStream(destinationUri) } returns outputStream

        val result = repository.moveBack(processingUri, originalParentUri, "bar.jpg")

        assertEquals(destinationUri, result)
        assertContentEquals(inputBytes, outputStream.toByteArray())
        verify(exactly = 1) { parentDocument.createFile("image/jpeg", "bar-1.jpg") }
        verify(exactly = 0) { parentDocument.createFile("image/jpeg", "bar.jpg") }
    }

    @Test
    fun `moveToProcessing advances suffix when original already ends with number`() = runTest {
        val safUri = Uri.parse("content://com.android.providers.documents/document/primary:Pictures/foo-2.jpg")
        val destinationUri = Uri.parse("content://com.example.destination/document/unique-suffix")
        val sourceDocument = mockk<DocumentFile>(relaxed = true)
        val destinationFolder = mockk<DocumentFile>(relaxed = true)
        val destinationDocument = mockk<DocumentFile>(relaxed = true)
        val existingBase = mockk<DocumentFile>(relaxed = true)
        val existingSuffixOne = mockk<DocumentFile>(relaxed = true)
        val existingSuffixTwo = mockk<DocumentFile>(relaxed = true)
        val inputBytes = "suffix".toByteArray()
        val outputStream = ByteArrayOutputStream()

        mockkStatic(DocumentFile::class)
        every { DocumentFile.fromSingleUri(context, safUri) } returns sourceDocument

        every { sourceDocument.type } returns "image/jpeg"
        every { sourceDocument.name } returns "foo-2.jpg"
        every { sourceDocument.uri } returns safUri
        every { sourceDocument.delete() } returns true

        every { existingBase.isFile } returns true
        every { existingBase.name } returns "foo.jpg"
        every { existingSuffixOne.isFile } returns true
        every { existingSuffixOne.name } returns "foo-1.jpg"
        every { existingSuffixTwo.isFile } returns true
        every { existingSuffixTwo.name } returns "foo-2.jpg"

        coEvery { processingFolderProvider.ensure() } returns destinationFolder
        every { destinationFolder.listFiles() } returns arrayOf(existingBase, existingSuffixOne, existingSuffixTwo)
        every { destinationFolder.createFile("image/jpeg", "foo-3.jpg") } returns destinationDocument
        every { destinationDocument.uri } returns destinationUri

        every { contentResolver.openInputStream(safUri) } returns ByteArrayInputStream(inputBytes)
        every { contentResolver.openOutputStream(destinationUri) } returns outputStream

        val result = repository.moveToProcessing(safUri)

        val success = assertIs<MoveResult.Success>(result)
        assertEquals(destinationUri, success.uri)
        assertContentEquals(inputBytes, outputStream.toByteArray())
        verify(exactly = 1) { destinationFolder.createFile("image/jpeg", "foo-3.jpg") }
    }
}
