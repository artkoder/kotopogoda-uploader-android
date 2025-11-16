package com.kotopogoda.uploader.core.data.deletion

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfirmDeletionUseCaseTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var repository: DeletionQueueRepository
    private lateinit var deleteRequestFactory: MediaStoreDeleteRequestFactory
    private lateinit var analytics: FakeDeletionAnalytics
    private lateinit var useCase: ConfirmDeletionUseCase

    @BeforeTest
    fun setUp() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        deleteRequestFactory = mockk(relaxed = true)
        analytics = FakeDeletionAnalytics()

        useCase = ConfirmDeletionUseCase(
            context = context,
            contentResolver = contentResolver,
            deletionQueueRepository = repository,
            deleteRequestFactory = deleteRequestFactory,
            deletionAnalytics = analytics,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @AfterTest
    fun tearDown() {
        analytics.clear()
    }

    // Сценарий (a): permissions missing -> PrepareResult.PermissionRequired

    @Test
    fun prepareReturnsPermissionRequiredWhenReadMediaImagesIsMissing() = runTest {
        // API 33+ требует READ_MEDIA_IMAGES
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
        every {
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)
        } returns PackageManager.PERMISSION_DENIED

        val result = useCase.prepare()

        assertIs<ConfirmDeletionUseCase.PrepareResult.PermissionRequired>(result)
        assertEquals(setOf(Manifest.permission.READ_MEDIA_IMAGES), result.permissions)
    }

    @Test
    fun prepareReturnsPermissionRequiredWhenReadExternalStorageIsMissing() = runTest {
        // API < 33 требует READ_EXTERNAL_STORAGE
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 29)
        every {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        } returns PackageManager.PERMISSION_DENIED

        val result = useCase.prepare()

        assertIs<ConfirmDeletionUseCase.PrepareResult.PermissionRequired>(result)
        assertEquals(setOf(Manifest.permission.READ_EXTERNAL_STORAGE), result.permissions)
    }

    // Сценарий (b): pending items chunked into batches respecting custom chunk size

    @Test
    fun prepareChunksPendingItemsIntoDefaultBatchSize() = runTest {
        // API 30+ использует createDeleteRequest и chunking
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 30)
        every {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        } returns PackageManager.PERMISSION_GRANTED

        // Создаем 5 pending items
        val pendingItems = (1..5).map { id ->
            DeletionItem(
                mediaId = id.toLong(),
                contentUri = "content://media/external/images/media/$id",
                displayName = "photo_$id.jpg",
                sizeBytes = 1000L * id,
                dateTaken = null,
                reason = "test_reason",
                status = DeletionItemStatus.PENDING,
                createdAt = System.currentTimeMillis(),
            )
        }
        coEvery { repository.getPending() } returns pendingItems

        val mockPendingIntent = mockk<PendingIntent>()
        val mockIntentSender = mockk<IntentSender>()
        every { mockPendingIntent.intentSender } returns mockIntentSender
        every {
            deleteRequestFactory.create(contentResolver, any())
        } returns mockPendingIntent

        // Используем chunk size = 2
        val result = useCase.prepare(chunkSize = 2)

        assertIs<ConfirmDeletionUseCase.PrepareResult.Ready>(result)
        // 5 items / 2 = 3 батча (2, 2, 1)
        assertEquals(3, result.batches.size)
        assertEquals(2, result.batches[0].items.size)
        assertEquals(2, result.batches[1].items.size)
        assertEquals(1, result.batches[2].items.size)
    }

    @Test
    fun prepareRespectsCustomChunkSize() = runTest {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 30)
        every {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        } returns PackageManager.PERMISSION_GRANTED

        val pendingItems = (1..7).map { id ->
            DeletionItem(
                mediaId = id.toLong(),
                contentUri = "content://media/external/images/media/$id",
                displayName = "photo_$id.jpg",
                sizeBytes = 500L,
                dateTaken = null,
                reason = "test_reason",
                status = DeletionItemStatus.PENDING,
                createdAt = System.currentTimeMillis(),
            )
        }
        coEvery { repository.getPending() } returns pendingItems

        val mockPendingIntent = mockk<PendingIntent>()
        val mockIntentSender = mockk<IntentSender>()
        every { mockPendingIntent.intentSender } returns mockIntentSender
        every {
            deleteRequestFactory.create(contentResolver, any())
        } returns mockPendingIntent

        // Используем chunk size = 3
        val result = useCase.prepare(chunkSize = 3)

        assertIs<ConfirmDeletionUseCase.PrepareResult.Ready>(result)
        // 7 items / 3 = 3 батча (3, 3, 1)
        assertEquals(3, result.batches.size)
        assertEquals(3, result.batches[0].items.size)
        assertEquals(3, result.batches[1].items.size)
        assertEquals(1, result.batches[2].items.size)
    }

    @Test
    fun prepareReturnsNoPendingWhenQueueIsEmpty() = runTest {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 30)
        every {
            context.checkSelfPermission(any())
        } returns PackageManager.PERMISSION_GRANTED
        coEvery { repository.getPending() } returns emptyList()

        val result = useCase.prepare()

        assertIs<ConfirmDeletionUseCase.PrepareResult.NoPending>(result)
    }

    // Сценарий (c): handleBatchResult marks successes/skip/fail correctly and aggregates freed bytes

    @Test
    fun handleBatchResultMarksSuccessesWhenFilesAreMissingAfterApproval() = runTest {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 30)

        val items = listOf(
            createBatchItem(1L, 1000L),
            createBatchItem(2L, 2000L),
            createBatchItem(3L, 3000L),
        )
        val batch = ConfirmDeletionUseCase.DeleteBatch(
            id = "batch-1",
            index = 0,
            items = items,
            intentSender = IntentSenderWrapper(mockk()),
            requiresRetryAfterApproval = false,
        )

        // Все файлы отсутствуют (успешно удалены)
        items.forEach { item ->
            mockQueryReturnsEmpty(item.uri)
        }

        coEvery { repository.markConfirmed(any()) } returns 3

        val result = useCase.handleBatchResult(batch, Activity.RESULT_OK, null)

        assertIs<ConfirmDeletionUseCase.BatchProcessingResult.Completed>(result)
        assertEquals(3, result.outcome.confirmedCount)
        assertEquals(0, result.outcome.failedCount)
        assertEquals(0, result.outcome.skippedCount)
        assertEquals(6000L, result.outcome.freedBytes) // 1000 + 2000 + 3000

        coVerify { repository.markConfirmed(listOf(1L, 2L, 3L)) }
    }

    @Test
    fun handleBatchResultMarksFailuresWhenFilesStillExist() = runTest {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 30)

        val items = listOf(
            createBatchItem(1L, 1000L),
            createBatchItem(2L, 2000L),
        )
        val batch = ConfirmDeletionUseCase.DeleteBatch(
            id = "batch-1",
            index = 0,
            items = items,
            intentSender = IntentSenderWrapper(mockk()),
            requiresRetryAfterApproval = false,
        )

        // Файлы все еще существуют (не удалены)
        items.forEach { item ->
            mockQueryReturnsRow(item.uri)
        }

        coEvery { repository.markFailed(any(), any()) } returns 2

        val result = useCase.handleBatchResult(batch, Activity.RESULT_OK, null)

        assertIs<ConfirmDeletionUseCase.BatchProcessingResult.Completed>(result)
        assertEquals(0, result.outcome.confirmedCount)
        assertEquals(2, result.outcome.failedCount)
        assertEquals(0, result.outcome.skippedCount)
        assertEquals(0L, result.outcome.freedBytes)

        coVerify { repository.markFailed(listOf(1L, 2L), "media_store_delete_failed") }
    }

    @Test
    fun handleBatchResultMixesSuccessesAndFailures() = runTest {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 30)

        val items = listOf(
            createBatchItem(1L, 1000L),
            createBatchItem(2L, 2000L),
            createBatchItem(3L, 3000L),
        )
        val batch = ConfirmDeletionUseCase.DeleteBatch(
            id = "batch-1",
            index = 0,
            items = items,
            intentSender = IntentSenderWrapper(mockk()),
            requiresRetryAfterApproval = false,
        )

        // Первый файл удален, остальные нет
        mockQueryReturnsEmpty(items[0].uri)
        mockQueryReturnsRow(items[1].uri)
        mockQueryReturnsRow(items[2].uri)

        coEvery { repository.markConfirmed(any()) } returns 1
        coEvery { repository.markFailed(any(), any()) } returns 2

        val result = useCase.handleBatchResult(batch, Activity.RESULT_OK, null)

        assertIs<ConfirmDeletionUseCase.BatchProcessingResult.Completed>(result)
        assertEquals(1, result.outcome.confirmedCount)
        assertEquals(2, result.outcome.failedCount)
        assertEquals(0, result.outcome.skippedCount)
        assertEquals(1000L, result.outcome.freedBytes)

        coVerify { repository.markConfirmed(listOf(1L)) }
        coVerify { repository.markFailed(listOf(2L, 3L), "media_store_delete_failed") }
    }

    @Test
    fun handleBatchResultWithRetryAfterApprovalMarksSuccesses() = runTest {
        val items = listOf(
            createBatchItem(1L, 1000L),
            createBatchItem(2L, 2000L),
        )
        val batch = ConfirmDeletionUseCase.DeleteBatch(
            id = "batch-1",
            index = 0,
            items = items,
            intentSender = IntentSenderWrapper(mockk()),
            requiresRetryAfterApproval = true,
        )

        // Мокаем успешное удаление при повторной попытке
        items.forEach { item ->
            every { contentResolver.delete(item.uri, null, null) } returns 1
        }

        coEvery { repository.markConfirmed(any()) } returns 2

        val result = useCase.handleBatchResult(batch, Activity.RESULT_OK, null)

        assertIs<ConfirmDeletionUseCase.BatchProcessingResult.Completed>(result)
        assertEquals(2, result.outcome.confirmedCount)
        assertEquals(0, result.outcome.failedCount)
        assertEquals(0, result.outcome.skippedCount)
        assertEquals(3000L, result.outcome.freedBytes)

        coVerify { repository.markConfirmed(listOf(1L, 2L)) }
    }

    @Test
    fun handleBatchResultWithRetryAfterApprovalMarksSkipped() = runTest {
        val items = listOf(
            createBatchItem(1L, 1000L),
            createBatchItem(2L, 2000L),
        )
        val batch = ConfirmDeletionUseCase.DeleteBatch(
            id = "batch-1",
            index = 0,
            items = items,
            intentSender = IntentSenderWrapper(mockk()),
            requiresRetryAfterApproval = true,
        )

        // Мокаем delete возвращает 0 (файл не найден)
        items.forEach { item ->
            every { contentResolver.delete(item.uri, null, null) } returns 0
        }

        coEvery { repository.markSkipped(any()) } returns 2

        val result = useCase.handleBatchResult(batch, Activity.RESULT_OK, null)

        assertIs<ConfirmDeletionUseCase.BatchProcessingResult.Completed>(result)
        assertEquals(0, result.outcome.confirmedCount)
        assertEquals(0, result.outcome.failedCount)
        assertEquals(2, result.outcome.skippedCount)
        assertEquals(0L, result.outcome.freedBytes)

        coVerify { repository.markSkipped(listOf(1L, 2L)) }
    }

    @Test
    fun handleBatchResultWithRetryAfterApprovalMarksFailuresOnException() = runTest {
        val items = listOf(
            createBatchItem(1L, 1000L),
        )
        val batch = ConfirmDeletionUseCase.DeleteBatch(
            id = "batch-1",
            index = 0,
            items = items,
            intentSender = IntentSenderWrapper(mockk()),
            requiresRetryAfterApproval = true,
        )

        // Мокаем SecurityException при повторной попытке
        every {
            contentResolver.delete(items[0].uri, null, null)
        } throws SecurityException("Access denied")

        coEvery { repository.markFailed(any(), any()) } returns 1

        val result = useCase.handleBatchResult(batch, Activity.RESULT_OK, null)

        assertIs<ConfirmDeletionUseCase.BatchProcessingResult.Completed>(result)
        assertEquals(0, result.outcome.confirmedCount)
        assertEquals(1, result.outcome.failedCount)
        assertEquals(0, result.outcome.skippedCount)

        coVerify { repository.markFailed(listOf(1L), "media_store_delete_failed") }
    }

    @Test
    fun handleBatchResultReturnsCancelledWhenUserCancels() = runTest {
        val batch = ConfirmDeletionUseCase.DeleteBatch(
            id = "batch-1",
            index = 0,
            items = emptyList(),
            intentSender = IntentSenderWrapper(mockk()),
            requiresRetryAfterApproval = false,
        )

        val result = useCase.handleBatchResult(batch, Activity.RESULT_CANCELED, null)

        assertIs<ConfirmDeletionUseCase.BatchProcessingResult.Cancelled>(result)
    }

    @Test
    fun reconcilePendingConfirmsMissingItems() = runTest {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 30)

        val pendingItems = listOf(
            DeletionItem(
                mediaId = 10L,
                contentUri = "content://media/external/images/media/10",
                displayName = "photo_10.jpg",
                sizeBytes = 1_024L,
                dateTaken = null,
                reason = "test_reason",
                status = DeletionItemStatus.PENDING,
                createdAt = System.currentTimeMillis(),
            ),
            DeletionItem(
                mediaId = 11L,
                contentUri = "content://media/external/images/media/11",
                displayName = "photo_11.jpg",
                sizeBytes = 2_048L,
                dateTaken = null,
                reason = "test_reason",
                status = DeletionItemStatus.PENDING,
                createdAt = System.currentTimeMillis() + 1,
            ),
        )
        coEvery { repository.getPending() } returns pendingItems
        pendingItems.forEach { item ->
            mockQueryReturnsEmpty(Uri.parse(item.contentUri))
        }
        coEvery { repository.markConfirmed(any()) } returns pendingItems.size

        val confirmed = useCase.reconcilePending()

        assertEquals(pendingItems.size, confirmed)
        coVerify { repository.markConfirmed(pendingItems.map { it.mediaId }) }
    }

    // Сценарий (d): retry-after-approval path triggers when RecoverableSecurityException is thrown

    @Test
    fun prepareCreatesRetryBatchOnRecoverableSecurityExceptionOnApi29() = runTest {
        // API 29 использует прямое удаление через contentResolver
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 29)
        every {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        } returns PackageManager.PERMISSION_GRANTED

        val pendingItems = listOf(
            DeletionItem(
                mediaId = 1L,
                contentUri = "content://media/external/images/media/1",
                displayName = "photo_1.jpg",
                sizeBytes = 1000L,
                dateTaken = null,
                reason = "test_reason",
                status = DeletionItemStatus.PENDING,
                createdAt = System.currentTimeMillis(),
            ),
            DeletionItem(
                mediaId = 2L,
                contentUri = "content://media/external/images/media/2",
                displayName = "photo_2.jpg",
                sizeBytes = 2000L,
                dateTaken = null,
                reason = "test_reason",
                status = DeletionItemStatus.PENDING,
                createdAt = System.currentTimeMillis(),
            ),
        )
        coEvery { repository.getPending() } returns pendingItems

        val uri1 = Uri.parse(pendingItems[0].contentUri)
        val uri2 = Uri.parse(pendingItems[1].contentUri)

        // Первый файл бросает RecoverableSecurityException
        val mockPendingIntent = mockk<PendingIntent>()
        val mockIntentSender = mockk<IntentSender>()
        every { mockPendingIntent.intentSender } returns mockIntentSender
        val recoverableException = mockk<RecoverableSecurityException>()
        every { recoverableException.userAction } returns mockk {
            every { actionIntent } returns mockPendingIntent
        }
        every { contentResolver.delete(uri1, null, null) } throws recoverableException

        // Второй файл успешно удаляется
        every { contentResolver.delete(uri2, null, null) } returns 1

        coEvery { repository.markConfirmed(any()) } returns 1

        val result = useCase.prepare()

        assertIs<ConfirmDeletionUseCase.PrepareResult.Ready>(result)
        // Должен быть 1 батч для файла, требующего подтверждения
        assertEquals(1, result.batches.size)
        assertTrue(result.batches[0].requiresRetryAfterApproval)
        assertEquals(1, result.batches[0].items.size)
        assertEquals(1L, result.batches[0].items[0].item.mediaId)

        // Проверяем initialOutcome содержит успешно удаленный файл
        assertEquals(1, result.initialOutcome.confirmedCount)
        assertEquals(0, result.initialOutcome.failedCount)
        assertEquals(2000L, result.initialOutcome.freedBytes)

        coVerify { repository.markConfirmed(listOf(2L)) }
    }

    @Test
    fun prepareMarksFailedOnNonRecoverableSecurityException() = runTest {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 29)
        every {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        } returns PackageManager.PERMISSION_GRANTED

        val pendingItems = listOf(
            DeletionItem(
                mediaId = 1L,
                contentUri = "content://media/external/images/media/1",
                displayName = "photo_1.jpg",
                sizeBytes = 1000L,
                dateTaken = null,
                reason = "test_reason",
                status = DeletionItemStatus.PENDING,
                createdAt = System.currentTimeMillis(),
            ),
        )
        coEvery { repository.getPending() } returns pendingItems

        val uri1 = Uri.parse(pendingItems[0].contentUri)

        // Бросаем обычный SecurityException (не recoverable)
        every {
            contentResolver.delete(uri1, null, null)
        } throws SecurityException("Access denied")

        coEvery { repository.markFailed(any(), any()) } returns 1

        val result = useCase.prepare()

        // На API 29 при неудачах возвращается Ready с пустым списком батчей и failures в initialOutcome
        assertIs<ConfirmDeletionUseCase.PrepareResult.Ready>(result)
        assertEquals(0, result.batches.size)
        assertEquals(0, result.initialOutcome.confirmedCount)
        assertEquals(1, result.initialOutcome.failedCount)

        coVerify { repository.markFailed(listOf(1L), "media_store_delete_failed") }
    }

    @Test
    fun prepareMarksFailedOnGenericException() = runTest {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 29)
        every {
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        } returns PackageManager.PERMISSION_GRANTED

        val pendingItems = listOf(
            DeletionItem(
                mediaId = 1L,
                contentUri = "content://media/external/images/media/1",
                displayName = "photo_1.jpg",
                sizeBytes = 1000L,
                dateTaken = null,
                reason = "test_reason",
                status = DeletionItemStatus.PENDING,
                createdAt = System.currentTimeMillis(),
            ),
        )
        coEvery { repository.getPending() } returns pendingItems

        val uri1 = Uri.parse(pendingItems[0].contentUri)

        // Бросаем произвольное исключение
        every {
            contentResolver.delete(uri1, null, null)
        } throws RuntimeException("Unexpected error")

        coEvery { repository.markFailed(any(), any()) } returns 1

        val result = useCase.prepare()

        // На API 29 при неудачах возвращается Ready с пустым списком батчей и failures в initialOutcome
        assertIs<ConfirmDeletionUseCase.PrepareResult.Ready>(result)
        assertEquals(0, result.batches.size)
        assertEquals(0, result.initialOutcome.confirmedCount)
        assertEquals(1, result.initialOutcome.failedCount)

        coVerify { repository.markFailed(listOf(1L), "media_store_delete_failed") }
    }

    // Вспомогательные методы

    private fun createBatchItem(mediaId: Long, sizeBytes: Long): ConfirmDeletionUseCase.BatchItem {
        val item = DeletionItem(
            mediaId = mediaId,
            contentUri = "content://media/external/images/media/$mediaId",
            displayName = "photo_$mediaId.jpg",
            sizeBytes = sizeBytes,
            dateTaken = null,
            reason = "test_reason",
            status = DeletionItemStatus.PENDING,
            createdAt = System.currentTimeMillis(),
        )
        return ConfirmDeletionUseCase.BatchItem(
            item = item,
            uri = Uri.parse(item.contentUri),
            resolvedSize = sizeBytes,
        )
    }

    private fun mockQueryReturnsEmpty(uri: Uri) {
        val emptyCursor = mockk<Cursor>(relaxed = true)
        every { emptyCursor.moveToFirst() } returns false
        every { emptyCursor.close() } returns Unit
        every {
            contentResolver.query(uri, any(), null, null, null)
        } returns emptyCursor
    }

    private fun mockQueryReturnsRow(uri: Uri) {
        val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID))
        cursor.addRow(arrayOf(123L))
        every {
            contentResolver.query(uri, any(), null, null, null)
        } returns cursor
    }
}
