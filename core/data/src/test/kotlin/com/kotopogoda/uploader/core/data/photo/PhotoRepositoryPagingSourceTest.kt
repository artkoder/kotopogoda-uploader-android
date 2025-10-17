package com.kotopogoda.uploader.core.data.photo

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadParams.Refresh
import androidx.paging.PagingSource.LoadResult.Page
import io.mockk.every
import io.mockk.isNull
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PhotoRepositoryPagingSourceTest {

    @Test
    fun `load falls back to date added when date taken missing`() = runTest {
        val baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val spec = createQuerySpec(listOf(baseUri))
        val contentResolver = mockk<ContentResolver>()
        val bundleSlot = slot<Bundle>()
        val sortOrderSlot = slot<String?>()

        every {
            contentResolver.query(baseUri, any(), capture(bundleSlot), isNull())
        } answers {
            MatrixCursor(PROJECTION).apply {
                addRow(
                    arrayOf(
                        1L,
                        null,
                        200L,
                        null,
                        null,
                        null,
                        "image/jpeg"
                    )
                )
                addRow(
                    arrayOf(
                        2L,
                        null,
                        100L,
                        null,
                        null,
                        null,
                        "image/jpeg"
                    )
                )
            }
        }
        every {
            contentResolver.query(baseUri, any(), any<String?>(), any(), capture(sortOrderSlot))
        } answers {
            MatrixCursor(PROJECTION).apply {
                addRow(
                    arrayOf(
                        1L,
                        null,
                        200L,
                        null,
                        null,
                        null,
                        "image/jpeg"
                    )
                )
                addRow(
                    arrayOf(
                        2L,
                        null,
                        100L,
                        null,
                        null,
                        null,
                        "image/jpeg"
                    )
                )
            }
        }

        val pagingSource = createPagingSource(contentResolver, spec)
        val result = pagingSource.load(Refresh(key = null, loadSize = 2, placeholdersEnabled = false))

        assertTrue(result is Page)
        val page = result as Page
        assertEquals(listOf("1", "2"), page.data.map(PhotoItem::id))
        assertEquals(
            listOf(Instant.ofEpochMilli(200_000), Instant.ofEpochMilli(100_000)),
            page.data.map(PhotoItem::takenAt)
        )
        if (bundleSlot.isCaptured) {
            val bundle = bundleSlot.captured
            assertNotNull(bundle)
            assertEquals(
                "$SORT_KEY_EXPRESSION DESC",
                bundle.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
            )
        } else {
            assertTrue(sortOrderSlot.isCaptured)
            assertEquals("$SORT_KEY_EXPRESSION DESC LIMIT 2", sortOrderSlot.captured)
        }
    }

    @Test
    fun `load prefers larger date added when date taken zero`() = runTest {
        val baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val spec = createQuerySpec(listOf(baseUri))
        val contentResolver = mockk<ContentResolver>()
        val bundleSlot = slot<Bundle>()
        val sortOrderSlot = slot<String?>()

        val legacyCursor = MatrixCursor(PROJECTION).apply {
            addRow(
                arrayOf(
                    1L,
                    0L,
                    1_700_000_000L,
                    null,
                    null,
                    null,
                    "image/jpeg"
                )
            )
            addRow(
                arrayOf(
                    2L,
                    1_640_995_200_000L,
                    1_600_000_000L,
                    null,
                    null,
                    null,
                    "image/jpeg"
                )
            )
        }

        every {
            contentResolver.query(baseUri, any(), capture(bundleSlot), isNull())
        } returns legacyCursor
        every {
            contentResolver.query(baseUri, any(), any<String?>(), any(), capture(sortOrderSlot))
        } returns legacyCursor

        val pagingSource = createPagingSource(contentResolver, spec)
        val result = pagingSource.load(Refresh(key = null, loadSize = 2, placeholdersEnabled = false))

        assertTrue(result is Page)
        val page = result as Page
        assertEquals(listOf("1", "2"), page.data.map(PhotoItem::id))
        assertEquals(
            listOf(
                Instant.ofEpochMilli(1_700_000_000_000L),
                Instant.ofEpochMilli(1_640_995_200_000L)
            ),
            page.data.map(PhotoItem::takenAt)
        )
        if (bundleSlot.isCaptured) {
            val bundle = bundleSlot.captured
            assertNotNull(bundle)
            assertEquals(
                "$SORT_KEY_EXPRESSION DESC",
                bundle.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
            )
        } else {
            assertTrue(sortOrderSlot.isCaptured)
            assertEquals("$SORT_KEY_EXPRESSION DESC LIMIT 2", sortOrderSlot.captured)
        }
    }

    private fun createPagingSource(contentResolver: ContentResolver, spec: Any): PagingSource<Int, PhotoItem> {
        val specClass = spec.javaClass
        val pagingSourceClass = Class.forName(PAGING_SOURCE_CLASS)
        val constructor = pagingSourceClass.getDeclaredConstructor(ContentResolver::class.java, specClass)
        constructor.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return constructor.newInstance(contentResolver, spec) as PagingSource<Int, PhotoItem>
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
        private const val PAGING_SOURCE_CLASS =
            "com.kotopogoda.uploader.core.data.photo.PhotoRepository\$MediaStorePhotoPagingSource"
        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE
        )
        private const val SORT_KEY_EXPRESSION =
            "CASE WHEN ${MediaStore.Images.Media.DATE_TAKEN} > 0 " +
                "THEN ${MediaStore.Images.Media.DATE_TAKEN} " +
                "ELSE ${MediaStore.Images.Media.DATE_ADDED} * 1000 END"
    }
}
