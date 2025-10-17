package com.kotopogoda.uploader.core.data.photo

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.isNull
import io.mockk.mockk
import io.mockk.spyk
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PhotoRepositoryTest {

    @Test
    fun `findIndexAtOrAfter uses normalized date expression for filtering`() = runTest {
        val folderRepository = mockk<FolderRepository>()
        val folder = Folder(
            id = 1,
            treeUri = "content://com.android.externalstorage.documents/tree/primary:Pictures",
            flags = 0,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        coEvery { folderRepository.getFolder() } returns folder

        val resolver = mockk<ContentResolver>()
        val context = mockk<Context> {
            every { contentResolver } returns resolver
        }

        val repository = spyk(
            PhotoRepository(folderRepository, context, Dispatchers.Unconfined),
            recordPrivateCalls = true
        )

        val contentUri = Uri.parse("content://media/external/images/media")
        val spec = createQuerySpec(listOf(contentUri))
        every { repository["buildQuerySpec"](folder) } returns spec

        val selectionValues = mutableListOf<String?>()
        val selectionArgsValues = mutableListOf<Array<String>?>()
        every { resolver.query(contentUri, any(), any(), isNull()) } answers {
            val bundle = thirdArg<Bundle?>() ?: Bundle()
            val selection = bundle.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
            val args = bundle.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
            selectionValues += selection
            selectionArgsValues += args
            if (selection != null) {
                MatrixCursor(arrayOf(MediaStore.Images.Media._ID))
            } else {
                MatrixCursor(arrayOf(MediaStore.Images.Media._ID)).apply {
                    addRow(arrayOf(1L))
                    addRow(arrayOf(2L))
                }
            }
        }

        val target = Instant.parse("2025-01-01T00:00:00Z")
        val result = repository.findIndexAtOrAfter(target)

        assertEquals(0, result)
        assertTrue(selectionValues.any { it == "${mediaStoreDatePriorityExpression()} > ?" })
        val args = selectionArgsValues.firstOrNull { it != null }
        assertEquals(listOf(target.toEpochMilli().toString()), args?.toList())
    }

    private fun createQuerySpec(contentUris: List<Uri>): Any {
        val specClass = Class.forName(QUERY_SPEC_CLASS)
        val constructor = specClass.getDeclaredConstructor(List::class.java, List::class.java, List::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(contentUris, emptyList<String>(), emptyList<String>())
    }

    companion object {
        private const val QUERY_SPEC_CLASS =
            "com.kotopogoda.uploader.core.data.photo.PhotoRepository\$MediaStoreQuerySpec"
    }
}
