package com.kotopogoda.uploader.core.network.work

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.api.UploadAcceptedDto
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.api.UploadLookupDto
import com.kotopogoda.uploader.core.network.api.UploadStatusDto
import com.kotopogoda.uploader.core.network.upload.UploadCleanupCoordinator
import com.kotopogoda.uploader.core.network.upload.UploadCleanupCoordinator.CleanupResult
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import io.mockk.coEvery
import io.mockk.mockk
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import android.app.PendingIntent
import android.app.Activity
import android.provider.MediaStore
import android.test.mock.MockContentResolver

@RunWith(AndroidJUnit4::class)
@MediumTest
class PollStatusWorkerMediaStoreTest {

    private lateinit var baseContext: Context
    private lateinit var resolver: RecordingContentResolver
    private lateinit var workerContext: Context
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var cleanupCoordinator: UploadCleanupCoordinator
    private lateinit var mediaStoreDeleteLauncher: RecordingDeleteLauncher
    private lateinit var uploadApi: UploadApi
    private lateinit var workerFactory: WorkerFactory

    @Before
    fun setUp() {
        baseContext = ApplicationProvider.getApplicationContext()
        resolver = RecordingContentResolver(baseContext)
        workerContext = ResolverContext(baseContext, resolver)
        TestForegroundDelegate.ensureChannel(workerContext)
        uploadQueueRepository = mockk(relaxed = true)
        coEvery { uploadQueueRepository.findSourceForItem(any()) } returns null
        cleanupCoordinator = mockk(relaxed = true)
        coEvery { cleanupCoordinator.handleUploadSuccess(any(), any(), any(), any(), any(), any()) } returns CleanupResult.Success(0L, 0)
        mediaStoreDeleteLauncher = RecordingDeleteLauncher(resolver)
        uploadApi = SuccessUploadApi()
        workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                if (workerClassName == PollStatusWorker::class.qualifiedName) {
                    return PollStatusWorker(
                        appContext,
                        workerParameters,
                        uploadApi,
                        uploadQueueRepository,
                        cleanupCoordinator,
                        TestForegroundDelegate(appContext),
                        NoopUploadSummaryStarter,
                        mediaStoreDeleteLauncher,
                    )
                }
                return null
            }
        }
        val configuration = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .setWorkerFactory(workerFactory)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(workerContext, configuration)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase(workerContext)
    }

    @Test
    fun mediaStoreItemRemovedAfterConfirmation() = runBlocking {
        val uri = mediaStoreUri(101)
        resolver.add(uri)
        resolver.requireConfirmation = true
        mediaStoreDeleteLauncher.nextResult = MediaStoreDeleteResult.Success

        val result = createWorker(uri).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertFalse(resolver.contains(uri))
        assertEquals(
            UploadEnqueuer.STATE_UPLOADED_DELETED,
            result.outputData.getString(UploadEnqueuer.KEY_COMPLETION_STATE)
        )
    }

    @Test
    fun mediaStoreItemRetainedWhenConfirmationCancelled() = runBlocking {
        val uri = mediaStoreUri(202)
        resolver.add(uri)
        resolver.requireConfirmation = true
        mediaStoreDeleteLauncher.nextResult = MediaStoreDeleteResult.Cancelled

        val result = createWorker(uri).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(resolver.contains(uri))
        assertEquals(
            UploadEnqueuer.STATE_UPLOADED_AWAITING_DELETE,
            result.outputData.getString(UploadEnqueuer.KEY_COMPLETION_STATE)
        )
    }

    private fun createWorker(uri: Uri): PollStatusWorker {
        val inputData = Data.Builder()
            .putLong(UploadEnqueuer.KEY_ITEM_ID, 1L)
            .putString(UploadEnqueuer.KEY_UPLOAD_ID, "upload-id")
            .putString(UploadEnqueuer.KEY_URI, uri.toString())
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, "photo.jpg")
            .build()
        return TestListenableWorkerBuilder<PollStatusWorker>(workerContext)
            .setWorkerFactory(workerFactory)
            .setInputData(inputData)
            .setId(UUID.randomUUID())
            .setForegroundUpdater(ForegroundUpdater { _, _ -> })
            .build() as PollStatusWorker
    }

    private fun mediaStoreUri(id: Long): Uri {
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            .buildUpon()
            .appendPath(id.toString())
            .build()
    }

    private class SuccessUploadApi : UploadApi {
        override suspend fun upload(
            idempotencyKey: String,
            contentSha256Header: String,
            body: RequestBody
        ): Response<UploadAcceptedDto> = throw UnsupportedOperationException()

        override suspend fun getStatus(uploadId: String): Response<UploadStatusDto> {
            return Response.success(UploadStatusDto(status = "done"))
        }

        override suspend fun getByIdempotencyKey(idempotencyKey: String): Response<UploadLookupDto> {
            return Response.success(UploadLookupDto(uploadId = null, status = null))
        }
    }

    private class ResolverContext(
        base: Context,
        private val resolver: RecordingContentResolver,
    ) : ContextWrapper(base) {
        override fun getContentResolver(): ContentResolver = resolver
    }

    private class RecordingContentResolver(
        private val context: Context,
    ) : MockContentResolver() {

        private val entries = mutableSetOf<Uri>()
        var requireConfirmation: Boolean = false

        fun add(uri: Uri) { entries += uri }
        fun contains(uri: Uri): Boolean = uri in entries
        fun remove(uri: Uri) { entries -= uri }

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int {
            if (uri !in entries) {
                return 0
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && requireConfirmation) {
                val intent = Intent(context, TestConfirmationActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                throw RecoverableSecurityException(
                    SecurityException("no access"),
                    "Need delete permission",
                    pendingIntent.intentSender,
                )
            }
            entries -= uri
            return 1
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): Cursor {
            val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID))
            if (uri in entries) {
                cursor.addRow(arrayOf(1L))
            }
            return cursor
        }
    }

    private class RecordingDeleteLauncher(
        private val resolver: RecordingContentResolver,
    ) : MediaStoreDeleteLauncher {
        var nextResult: MediaStoreDeleteResult = MediaStoreDeleteResult.Success
        val launchedUris = mutableListOf<Uri>()

        override suspend fun requestDelete(resolver: ContentResolver, uri: Uri): MediaStoreDeleteResult {
            launchedUris += uri
            val result = nextResult
            if (result is MediaStoreDeleteResult.Success) {
                this.resolver.remove(uri)
            }
            return result
        }
    }

    class TestConfirmationActivity : Activity() {
        override fun onStart() {
            super.onStart()
            setResult(RESULT_OK)
            finish()
        }
    }
}
