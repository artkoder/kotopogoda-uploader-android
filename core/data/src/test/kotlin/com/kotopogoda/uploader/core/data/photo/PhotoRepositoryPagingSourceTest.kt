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

        every {
            contentResolver.query(baseUri, any(), capture(bundleSlot), isNull())
        } answers {
            MatrixCursor(PROJECTION).apply {
                addRow(arrayOf(1L, null, 200L, null, null, null, "image/jpeg"))
                addRow(arrayOf(2L, null, 100L, null, null, null, "image/jpeg"))
            }
        }

        val pagingSource = createPagingSource(contentResolver, spec)
        val result = pagingSource.load(Refresh(key = null, loadSize = 2, placeholdersEnabled = false))

        assertTrue(result is Page)
        val page = result as Page
        assertEquals(listOf("1", "2"), page.data.map(PhotoItem::id))
        assertEquals(
            listOf(Instant.ofEpochSecond(200), Instant.ofEpochSecond(100)),
            page.data.map(PhotoItem::takenAt)
        )
        val bundle = bundleSlot.captured
        assertNotNull(bundle)
        assertEquals(
            "${mediaStoreDatePriorityExpression()} DESC",
            bundle.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER)
        )
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
    }
}
