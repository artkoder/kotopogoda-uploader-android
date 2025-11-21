package com.kotopogoda.uploader.core.data.photo

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.kotopogoda.uploader.core.data.folder.Folder
import com.kotopogoda.uploader.core.data.folder.FolderRepository
import io.mockk.*
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoRepositoryTest {

    private val testScheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(testScheduler)

    @Test
    fun `findIndexAtOrAfter prefers date added when date taken missing`() = runTest {
        val freshTakenAt = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli()
        val legacyAdded = Instant.parse("2022-01-01T00:00:00Z").epochSecond
        val target = Instant.parse("2025-01-01T00:00:00Z")
        val environment = createRepositoryEnvironment(
            listOf(
                FakePhoto(dateTakenMillis = freshTakenAt, dateAddedSeconds = null, dateModifiedSeconds = null),
                FakePhoto(dateTakenMillis = null, dateAddedSeconds = legacyAdded, dateModifiedSeconds = null)
            )
        )

        val index = environment.repository.findIndexAtOrAfter(target)

        assertEquals(0, index)
        val selection = environment.selectionHistory.firstOrNull()
        assertNotNull(selection)
        assertTrue(selection.contains(MediaStore.Images.Media.DATE_ADDED))
        val args = environment.argsHistory.firstOrNull()
        assertNotNull(args)
        assertEquals(target.toEpochMilli().toString(), args.first())
    }

    @Test
    fun `findIndexAtOrAfter returns first index in day when photos exist`() = runTest {
        val environment = createRepositoryEnvironment(
            listOf(
                FakePhoto(
                    dateTakenMillis = Instant.parse("2025-01-03T00:00:00Z").toEpochMilli(),
                    dateAddedSeconds = null,
                    dateModifiedSeconds = null
                ),
                FakePhoto(
                    dateTakenMillis = null,
                    dateAddedSeconds = Instant.parse("2025-01-02T12:00:00Z").epochSecond,
                    dateModifiedSeconds = null
                ),
                FakePhoto(
                    dateTakenMillis = null,
                    dateAddedSeconds = null,
                    dateModifiedSeconds = Instant.parse("2025-01-02T01:00:00Z").epochSecond
                ),
                FakePhoto(
                    dateTakenMillis = Instant.parse("2025-01-01T23:00:00Z").toEpochMilli(),
                    dateAddedSeconds = null,
                    dateModifiedSeconds = null
                )
            )
        )
        val zone = ZoneId.systemDefault()
        val localDate = Instant.parse("2025-01-02T10:00:00Z").atZone(zone).toLocalDate()
        val startOfDay = localDate.atStartOfDay(zone).toInstant()
        val endOfDay = localDate.plusDays(1).atStartOfDay(zone).toInstant()

        val index = environment.repository.findIndexAtOrAfter(startOfDay, endOfDay)

        assertEquals(1, index)
        assertTrue(environment.selectionHistory.any { it?.contains(MediaStore.Images.Media.DATE_MODIFIED) == true })
    }

    @Test
    fun `findIndexAtOrAfter returns null when day has no photos`() = runTest {
        val environment = createRepositoryEnvironment(
            listOf(
                FakePhoto(
                    dateTakenMillis = Instant.parse("2025-01-03T00:00:00Z").toEpochMilli(),
                    dateAddedSeconds = null,
                    dateModifiedSeconds = null
                ),
                FakePhoto(
                    dateTakenMillis = null,
                    dateAddedSeconds = null,
                    dateModifiedSeconds = Instant.parse("2025-01-01T23:00:00Z").epochSecond
                )
            )
        )
        val zone = ZoneId.systemDefault()
        val localDate = Instant.parse("2025-01-02T10:00:00Z").atZone(zone).toLocalDate()
        val startOfDay = localDate.atStartOfDay(zone).toInstant()
        val endOfDay = localDate.plusDays(1).atStartOfDay(zone).toInstant()

        val index = environment.repository.findIndexAtOrAfter(startOfDay, endOfDay)

        assertNull(index)
    }

    @Test
    fun `getAvailableDates uses Bundle on Android R+`() = runTest {
        val environment = createRepositoryEnvironment(
            listOf(
                FakePhoto(
                    dateTakenMillis = Instant.parse("2025-01-03T00:00:00Z").toEpochMilli(),
                    dateAddedSeconds = null,
                    dateModifiedSeconds = null
                )
            )
        )

        val dates = environment.repository.getAvailableDates()

        assertEquals(1, dates.size)
        verify {
            environment.contentResolver.query(
                any(),
                any(),
                any<Bundle>(),
                any()
            )
        }
    }

    @Test
    fun `findIndexAtOrAfter uses consistent sort key logic for future dates`() = runTest {
        val targetDate = Instant.parse("2024-10-02T00:00:00Z")
        val futureDate = Instant.parse("2025-06-07T19:31:00Z")

        val environment = createRepositoryEnvironment(
            listOf(
                FakePhoto(
                    dateTakenMillis = futureDate.toEpochMilli(),
                    dateAddedSeconds = null,
                    dateModifiedSeconds = null
                ),
                FakePhoto(
                    dateTakenMillis = Instant.parse("2022-01-01T00:00:00Z").toEpochMilli(),
                    dateAddedSeconds = null,
                    dateModifiedSeconds = null
                )
            )
        )

        val index = environment.repository.findIndexAtOrAfter(targetDate)

        assertEquals(1, index)
    }

    @Test
    fun `findIndexAtOrAfter uses consistent sort key logic when falling back to date added`() = runTest {
        val targetDate = Instant.parse("2024-10-02T00:00:00Z")
        val futureAdded = Instant.parse("2025-06-07T19:31:00Z").epochSecond

        val environment = createRepositoryEnvironment(
            listOf(
                FakePhoto(
                    dateTakenMillis = null,
                    dateAddedSeconds = futureAdded,
                    dateModifiedSeconds = null
                ),
                FakePhoto(
                    dateTakenMillis = null,
                    dateAddedSeconds = Instant.parse("2022-01-01T00:00:00Z").epochSecond,
                    dateModifiedSeconds = null
                )
            )
        )

        val index = environment.repository.findIndexAtOrAfter(targetDate)

        assertEquals(1, index)
    }

    private fun createRepositoryEnvironment(
        photos: List<FakePhoto>
    ): RepositoryEnvironment {
        val folderRepository = mockk<FolderRepository>()
        val context = mockk<Context>()
        val contentResolver = mockk<ContentResolver>()
        every { context.contentResolver } returns contentResolver

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

        fun buildCursor(selection: String?, args: Array<String>?): MatrixCursor {
            selectionHistory += selection
            argsHistory += args
            val filtered = photos.filter { photo -> matchesSelection(photo, selection, args) }
            return MatrixCursor(arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED)).apply {
                filtered.forEachIndexed { index, photo ->
                    val row = arrayOf<Any?>(
                        index.toLong(),
                        photo.dateTakenMillis,
                        photo.dateAddedSeconds
                    )
                    addRow(row)
                }
            }
        }

        every {
            contentResolver.query(baseUri, any(), any(), any())
        } answers {
            val bundle = thirdArg<Bundle>()
            val selection = bundle.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
            val args = bundle.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)
            buildCursor(selection, args)
        }
        every {
            contentResolver.query(baseUri, any(), any(), any(), any())
        } answers {
            val selection = thirdArg<String?>()
            val args = arg<Array<String>?>(3)
            buildCursor(selection, args)
        }

        return RepositoryEnvironment(repository, contentResolver, selectionHistory, argsHistory)
    }

    private fun createQuerySpec(contentUris: List<Uri>): Any {
        val specClass = Class.forName(QUERY_SPEC_CLASS)
        val constructor = specClass.getDeclaredConstructor(List::class.java, List::class.java, List::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(contentUris, emptyList<String>(), emptyList<String>())
    }

    private data class RepositoryEnvironment(
        val repository: PhotoRepository,
        val contentResolver: ContentResolver,
        val selectionHistory: MutableList<String?>,
        val argsHistory: MutableList<Array<String>?>
    )

    private data class FakePhoto(
        val dateTakenMillis: Long?,
        val dateAddedSeconds: Long?,
        val dateModifiedSeconds: Long?
    ) {
        fun effectiveTimestampMillis(): Long? {
            val taken = dateTakenMillis
            if (taken != null && taken > 0) {
                return taken
            }
            val addedSeconds = dateAddedSeconds
            if (addedSeconds != null && addedSeconds > 0) {
                return addedSeconds * 1000
            }
            val modifiedSeconds = dateModifiedSeconds
            if (modifiedSeconds != null && modifiedSeconds > 0) {
                return modifiedSeconds * 1000
            }
            return null
        }
    }

    private fun matchesSelection(
        photo: FakePhoto,
        selection: String?,
        args: Array<String>?
    ): Boolean {
        if (selection.isNullOrBlank()) {
            return true
        }

        if (selection.contains(PhotoRepository.SORT_KEY_EXPRESSION)) {
             val timestamp = photo.effectiveTimestampMillis() ?: return false
             val thresholds = args?.mapNotNull { it.toLongOrNull() } ?: emptyList()

             if (selection.contains("> ?")) {
                 val threshold = thresholds.getOrElse(0) { Long.MIN_VALUE }
                 return timestamp > threshold
             }
             if (selection.contains(">= ?") && !selection.contains("< ?")) {
                 val threshold = thresholds.getOrElse(0) { Long.MIN_VALUE }
                 return timestamp >= threshold
             }
             if (selection.contains(">= ?") && selection.contains("< ?")) {
                 val start = thresholds.getOrElse(0) { Long.MIN_VALUE }
                 val end = thresholds.getOrElse(1) { Long.MAX_VALUE }
                 return timestamp >= start && timestamp < end
             }
        }

        val thresholds = args?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        val timestamp = photo.effectiveTimestampMillis() ?: return false
        return when {
            selection.contains("${MediaStore.Images.Media.DATE_TAKEN} < ?") -> {
                val lower = thresholds.getOrElse(0) { Long.MIN_VALUE }
                val upper = thresholds.getOrElse(1) { Long.MAX_VALUE }
                timestamp in lower until upper
            }
            selection.contains("${MediaStore.Images.Media.DATE_TAKEN} >= ?") -> {
                val threshold = thresholds.getOrElse(0) { Long.MIN_VALUE }
                timestamp >= threshold
            }
            selection.contains("${MediaStore.Images.Media.DATE_TAKEN} > ?") -> {
                val threshold = thresholds.getOrElse(0) { Long.MIN_VALUE }
                timestamp > threshold
            }
            else -> false
        }
    }

    companion object {
        private const val QUERY_SPEC_CLASS =
            "com.kotopogoda.uploader.core.data.photo.PhotoRepository\$MediaStoreQuerySpec"
    }
}
