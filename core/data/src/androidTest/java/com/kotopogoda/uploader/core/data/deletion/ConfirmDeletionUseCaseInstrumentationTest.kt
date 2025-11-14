package com.kotopogoda.uploader.core.data.deletion

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kotopogoda.uploader.core.data.database.KotopogodaDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfirmDeletionUseCaseInstrumentationTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var database: KotopogodaDatabase
    private lateinit var repository: DeletionQueueRepository
    private lateinit var analytics: FakeDeletionAnalytics
    private lateinit var useCase: ConfirmDeletionUseCase
    private lateinit var deleteRequestFactory: MediaStoreDeleteRequestFactory

    private val insertedUris = mutableListOf<Uri>()
    private val fixedClock = Clock.fixed(Instant.parse("2024-01-01T12:00:00Z"), ZoneId.of("UTC"))

    @Before
    fun setUp() {
        // Тест работает только на API 30+
        Assume.assumeTrue("Тест требует API 30+", Build.VERSION.SDK_INT >= 30)

        context = ApplicationProvider.getApplicationContext()
        contentResolver = context.contentResolver

        // Создаем in-memory БД
        database = Room.inMemoryDatabaseBuilder(
            context,
            KotopogodaDatabase::class.java
        ).build()

        analytics = FakeDeletionAnalytics()
        repository = DeletionQueueRepository(
            deletionItemDao = database.deletionItemDao(),
            clock = fixedClock,
            deletionAnalytics = analytics
        )

        deleteRequestFactory = MediaStoreDeleteRequestFactory()
        useCase = ConfirmDeletionUseCase(
            context = context,
            contentResolver = contentResolver,
            deletionQueueRepository = repository,
            deleteRequestFactory = deleteRequestFactory,
            deletionAnalytics = analytics
        )
    }

    @After
    fun tearDown() {
        // Очищаем все вставленные записи
        insertedUris.forEach { uri ->
            try {
                contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                // Игнорируем ошибки при очистке
            }
        }
        insertedUris.clear()

        database.close()
    }

    @Test
    fun testConfirmDeletionFlowWithRealMediaStore() = runTest {
        // Шаг 1: Вставляем временные медиа-элементы в MediaStore
        val uri1 = insertTestImageToMediaStore("test_image_1.jpg", 1024L)
        val uri2 = insertTestImageToMediaStore("test_image_2.jpg", 2048L)

        insertedUris.add(uri1)
        insertedUris.add(uri2)

        // Получаем media ID из URI
        val mediaId1 = uri1.lastPathSegment?.toLongOrNull() ?: 1L
        val mediaId2 = uri2.lastPathSegment?.toLongOrNull() ?: 2L

        // Шаг 2: Создаем DeletionRequest и ставим в очередь
        val requests = listOf(
            DeletionRequest(
                mediaId = mediaId1,
                contentUri = uri1.toString(),
                displayName = "test_image_1.jpg",
                sizeBytes = 1024L,
                dateTaken = System.currentTimeMillis(),
                reason = "instrumentation_test"
            ),
            DeletionRequest(
                mediaId = mediaId2,
                contentUri = uri2.toString(),
                displayName = "test_image_2.jpg",
                sizeBytes = 2048L,
                dateTaken = System.currentTimeMillis(),
                reason = "instrumentation_test"
            )
        )

        repository.enqueue(requests)

        // Проверяем, что элементы в очереди
        val pending = repository.getPending()
        assertEquals(2, pending.size)

        // Шаг 3: Вызываем prepare() для получения батчей
        val prepareResult = useCase.prepare()

        assertTrue(prepareResult is ConfirmDeletionUseCase.PrepareResult.Ready)
        val readyResult = prepareResult as ConfirmDeletionUseCase.PrepareResult.Ready
        assertTrue(readyResult.batches.isNotEmpty(), "Должен быть хотя бы один батч")

        // Шаг 4: Симулируем подтверждение пользователя через handleBatchResult
        val batch = readyResult.batches.first()
        val batchResult = useCase.handleBatchResult(batch, Activity.RESULT_OK, null)

        assertTrue(batchResult is ConfirmDeletionUseCase.BatchProcessingResult.Completed)
        val completedResult = batchResult as ConfirmDeletionUseCase.BatchProcessingResult.Completed

        // Шаг 5: Проверяем, что записи удалены из MediaStore
        val uri1Exists = checkUriExists(uri1)
        val uri2Exists = checkUriExists(uri2)

        assertTrue(!uri1Exists, "URI $uri1 должен быть удален из MediaStore")
        assertTrue(!uri2Exists, "URI $uri2 должен быть удален из MediaStore")

        // Шаг 6: Проверяем, что репозиторий пометил элементы как confirmed
        val afterPending = repository.getPending()
        assertEquals(0, afterPending.size, "Очередь должна быть пустой после подтверждения")

        // Проверяем, что freedBytes > 0
        assertTrue(completedResult.outcome.freedBytes > 0, "Должны быть освобождены байты")

        // Проверяем аналитику
        val confirmedEvents = analytics.events.filterIsInstance<FakeDeletionAnalytics.DeletionEvent.Confirmed>()
        assertTrue(confirmedEvents.isNotEmpty(), "Должно быть событие Confirmed")
    }

    @Test
    fun testCancelledDeletionDoesNotRemoveItems() = runTest {
        // Вставляем тестовый элемент
        val uri = insertTestImageToMediaStore("test_cancelled.jpg", 512L)
        insertedUris.add(uri)

        val mediaId = uri.lastPathSegment?.toLongOrNull() ?: 1L

        val request = DeletionRequest(
            mediaId = mediaId,
            contentUri = uri.toString(),
            displayName = "test_cancelled.jpg",
            sizeBytes = 512L,
            dateTaken = System.currentTimeMillis(),
            reason = "test_cancel"
        )

        repository.enqueue(listOf(request))

        val prepareResult = useCase.prepare()
        assertTrue(prepareResult is ConfirmDeletionUseCase.PrepareResult.Ready)

        val batch = (prepareResult as ConfirmDeletionUseCase.PrepareResult.Ready).batches.first()

        // Симулируем отмену пользователем
        val batchResult = useCase.handleBatchResult(batch, Activity.RESULT_CANCELED, null)

        assertTrue(batchResult is ConfirmDeletionUseCase.BatchProcessingResult.Cancelled)

        // Проверяем, что URI все еще существует
        val uriExists = checkUriExists(uri)
        assertTrue(uriExists, "URI должен остаться после отмены")

        // Проверяем, что элемент все еще в очереди
        val pending = repository.getPending()
        assertEquals(1, pending.size, "Элемент должен остаться в очереди после отмены")
    }

    @Test
    fun testNoPendingWhenQueueIsEmpty() = runTest {
        // Не вставляем элементы, очередь пустая
        val prepareResult = useCase.prepare()

        assertTrue(prepareResult is ConfirmDeletionUseCase.PrepareResult.NoPending)
    }

    @Test
    fun testFreedBytesCalculation() = runTest {
        val size1 = 1500L
        val size2 = 2500L

        val uri1 = insertTestImageToMediaStore("test_bytes_1.jpg", size1)
        val uri2 = insertTestImageToMediaStore("test_bytes_2.jpg", size2)

        insertedUris.add(uri1)
        insertedUris.add(uri2)

        val mediaId1 = uri1.lastPathSegment?.toLongOrNull() ?: 1L
        val mediaId2 = uri2.lastPathSegment?.toLongOrNull() ?: 2L

        val requests = listOf(
            DeletionRequest(
                mediaId = mediaId1,
                contentUri = uri1.toString(),
                displayName = "test_bytes_1.jpg",
                sizeBytes = size1,
                dateTaken = System.currentTimeMillis(),
                reason = "test_bytes"
            ),
            DeletionRequest(
                mediaId = mediaId2,
                contentUri = uri2.toString(),
                displayName = "test_bytes_2.jpg",
                sizeBytes = size2,
                dateTaken = System.currentTimeMillis(),
                reason = "test_bytes"
            )
        )

        repository.enqueue(requests)

        val prepareResult = useCase.prepare()
        assertTrue(prepareResult is ConfirmDeletionUseCase.PrepareResult.Ready)

        val batch = (prepareResult as ConfirmDeletionUseCase.PrepareResult.Ready).batches.first()
        val batchResult = useCase.handleBatchResult(batch, Activity.RESULT_OK, null)

        assertTrue(batchResult is ConfirmDeletionUseCase.BatchProcessingResult.Completed)
        val outcome = (batchResult as ConfirmDeletionUseCase.BatchProcessingResult.Completed).outcome

        // Проверяем, что освобожденные байты соответствуют сумме размеров
        val expectedBytes = size1 + size2
        assertEquals(expectedBytes, outcome.freedBytes, "Освобожденные байты должны совпадать")
    }

    /**
     * Вставляет тестовое изображение в MediaStore и возвращает URI
     */
    private fun insertTestImageToMediaStore(displayName: String, size: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.SIZE, size)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KotopogodaTest")
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Не удалось вставить тестовый элемент в MediaStore")

        // Записываем минимальные данные в файл
        contentResolver.openOutputStream(uri)?.use { output ->
            // Записываем минимальный JPEG заголовок + данные
            output.write(ByteArray(size.toInt()) { 0xFF.toByte() })
        }

        return uri
    }

    /**
     * Проверяет, существует ли URI в MediaStore
     */
    private fun checkUriExists(uri: Uri): Boolean {
        return try {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { cursor ->
                cursor.moveToFirst()
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
