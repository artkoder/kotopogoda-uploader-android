package com.kotopogoda.uploader.core.data.photo

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import io.mockk.any
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PhotoRepositoryTest {

    @Test
    fun `findIndexAtOrAfter prefers date added when date taken missing`() = runTest {
        val folderRepository = mockk<FolderRepository>()
        val context = mockk<Context>()
        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = spyk(
            PhotoRepository(folderRepository, context, dispatcher),
            recordPrivateCalls = true
        )

        val folder = Folder(
            id = 1,
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADCIM",
            flags = 0,
            lastScanAt = null,
            lastViewedPhotoId = null,
            lastViewedAt = null
        )
        coEvery { folderRepository.getFolder() } returns folder

        val baseUri = Uri.parse("content://media/external/images/media")
        val spec = createQuerySpec(listOf(baseUri))
        every { repository["buildQuerySpec"](any<Folder>()) } returns spec

        val selectionHistory = mutableListOf<String?>()
        val argsHistory = mutableListOf<Array<String>?>()
        val freshSortKey = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli()
        val legacySortKey = Instant.parse("2022-01-01T00:00:00Z").toEpochMilli()
        val target = Instant.parse("2025-01-01T00:00:00Z")

        fun buildCursor(selection: String?, args: Array<String>?): MatrixCursor {
            selectionHistory += selection
            argsHistory += args
            val relevantSortKeys = when (selection) {
                null, "" -> listOf(freshSortKey, legacySortKey)
                "$SORT_KEY_EXPRESSION > ?" -> {
                    val threshold = args?.firstOrNull()?.toLongOrNull() ?: Long.MIN_VALUE
                    listOf(freshSortKey, legacySortKey).filter { it > threshold }
                }
                else -> listOf(freshSortKey, legacySortKey)
            }
            return MatrixCursor(arrayOf(MediaStore.Images.Media._ID)).apply {
                relevantSortKeys.forEachIndexed { index, _ ->
                    addRow(arrayOf(index.toLong()))
                }
            }
        }

        every {
            contentResolver.query(baseUri, any(), any<Bundle>(), any())
        } answers {
            val bundle = thirdArg<Bundle>()
            val selection = bundle.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
            val args = bundle.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
            buildCursor(selection, args)
        }
        every {
            contentResolver.query(baseUri, any(), any<String?>(), any(), any())
        } answers {
            val selection = thirdArg<String?>()
            val args = arg<Array<String>?>(3)
            buildCursor(selection, args)
        }

        val index = repository.findIndexAtOrAfter(target)

        assertEquals(0, index)
        val selection = selectionHistory.firstOrNull()
        assertNotNull(selection)
        assertEquals("$SORT_KEY_EXPRESSION > ?", selection)
        val args = argsHistory.firstOrNull()
        assertNotNull(args)
        assertEquals(target.toEpochMilli().toString(), args.first())
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
        private const val SORT_KEY_EXPRESSION =
            "CASE WHEN ${MediaStore.Images.Media.DATE_TAKEN} > 0 " +
                "THEN ${MediaStore.Images.Media.DATE_TAKEN} " +
                "ELSE ${MediaStore.Images.Media.DATE_ADDED} * 1000 END"
    }
}
