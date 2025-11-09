package com.kotopogoda.uploader.core.data.sa

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
 * Тесты для операций с SAF (Storage Access Framework) документами
 * Разделено на отдельный класс для снижения потребления памяти при mockkинге
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SaFileRepositoryTest_SafDocuments {

    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val processingFolderProvider = mockk<ProcessingFolderProvider>(relaxed = true)
    private val repository = SaFileRepository(context, processingFolderProvider)

    init {
        every { context.contentResolver } returns contentResolver
    }

    @Before
    fun setUp() {
        mockkStatic(DocumentFile::class)
    }

    @After
    fun tearDown() {
        // Не очищаем моки для экономии памяти - static моки остаются активными
        // Каждый тест создает свои экземпляры mock объектов
    }

    @Test
    fun `moveToProcessing copies and deletes SAF document`() = runTest {
        val safUri = Uri.parse("content://com.android.providers.documents/document/primary:Pictures/foo.jpg")
        val destinationUri = Uri.parse("content://com.example.destination/document/1")
        val sourceDocument = mockk<DocumentFile>(relaxed = true)
        val destinationFolder = mockk<DocumentFile>()
        val destinationDocument = mockk<DocumentFile>()
        val inputBytes = "hello-world".toByteArray()
        val outputStream = ByteArrayOutputStream()

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
    fun `moveToProcessing generates unique name when SAF destination has duplicate`() = runTest {
        val safUri = Uri.parse("content://com.android.providers.documents/document/primary:Pictures/foo.jpg")
        val destinationUri = Uri.parse("content://com.example.destination/document/unique")
        val sourceDocument = mockk<DocumentFile>(relaxed = true)
        val destinationFolder = mockk<DocumentFile>()
        val destinationDocument = mockk<DocumentFile>()
        val existingDocument = mockk<DocumentFile>(relaxed = true)
        val inputBytes = "duplicate".toByteArray()
        val outputStream = ByteArrayOutputStream()

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
    fun `moveToProcessing advances suffix when original already ends with number`() = runTest {
        val safUri = Uri.parse("content://com.android.providers.documents/document/primary:Pictures/foo-2.jpg")
        val destinationUri = Uri.parse("content://com.example.destination/document/unique-suffix")
        val sourceDocument = mockk<DocumentFile>(relaxed = true)
        val destinationFolder = mockk<DocumentFile>()
        val destinationDocument = mockk<DocumentFile>()
        val existingBase = mockk<DocumentFile>(relaxed = true)
        val existingSuffixOne = mockk<DocumentFile>(relaxed = true)
        val existingSuffixTwo = mockk<DocumentFile>(relaxed = true)
        val inputBytes = "suffix".toByteArray()
        val outputStream = ByteArrayOutputStream()

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

    @Test
    fun `moveBack recreates file with unique name when duplicate exists`() = runTest {
        val processingUri = Uri.parse("content://com.example.processing/document/processing")
        val originalParentUri = Uri.parse("content://com.example.destination/tree/original")
        val destinationUri = Uri.parse("content://com.example.destination/document/restored")
        val sourceDocument = mockk<DocumentFile>(relaxed = true)
        val parentDocument = mockk<DocumentFile>()
        val destinationDocument = mockk<DocumentFile>()
        val existingDocument = mockk<DocumentFile>(relaxed = true)
        val inputBytes = "restore".toByteArray()
        val outputStream = ByteArrayOutputStream()

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
}
