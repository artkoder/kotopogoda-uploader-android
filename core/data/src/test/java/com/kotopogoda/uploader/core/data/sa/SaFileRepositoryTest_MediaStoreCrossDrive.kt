package com.kotopogoda.uploader.core.data.sa

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.app.RemoteAction
import android.content.ContentResolver
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
 * Тесты для операций с MediaStore документами между разными томами
 * Разделено на отдельный класс для снижения потребления памяти при mockkинге
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SaFileRepositoryTest_MediaStoreCrossDrive {

    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val processingFolderProvider = mockk<ProcessingFolderProvider>(relaxed = true)
    private val repository = SaFileRepository(context, processingFolderProvider)
    private val originalMediaStoreVolumeResolver = mediaStoreVolumeResolver
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
        mediaStoreDeleteRequestFactory = originalMediaStoreDeleteRequestFactory
        clearAllMocks(answers = false)
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

        every { DocumentFile.fromSingleUri(context, any()) } throws AssertionError("Should not access DocumentFile for MediaStore URIs")

        mediaStoreVolumeResolver = { MediaStore.VOLUME_EXTERNAL_PRIMARY }

        every { MediaStore.createDeleteRequest(contentResolver, listOf(mediaUri)) } returns pendingIntent
        every { pendingIntent.intentSender } returns intentSender

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

        every { DocumentFile.fromSingleUri(context, any()) } throws AssertionError("Should not access DocumentFile for MediaStore URIs")

        mediaStoreVolumeResolver = { MediaStore.VOLUME_EXTERNAL_PRIMARY }

        every { MediaStore.createDeleteRequest(contentResolver, listOf(mediaUri)) } returns pendingIntent
        every { pendingIntent.intentSender } returns intentSender

        every { DocumentsContract.getDocumentId(destinationFolderUri) } returns "1234-5678:Kotopogода/На обработку"

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
}
