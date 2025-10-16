package com.kotopogoda.uploader.core.network.work

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result.Failure
import androidx.work.ListenableWorker.Result.Retry
import androidx.work.ListenableWorker.Result.Success
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import io.mockk.mockk
import androidx.work.ForegroundUpdater
import org.robolectric.RobolectricTestRunner
import androidx.work.Configuration

@RunWith(RobolectricTestRunner::class)
class PollStatusWorkerTest {

    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer
    private lateinit var uploadApi: UploadApi
    private lateinit var workerFactory: WorkerFactory
    private lateinit var uploadQueueRepository: UploadQueueRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val configuration = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        TestForegroundDelegate.ensureChannel(context)
        mockWebServer = MockWebServer().apply { start() }
        uploadQueueRepository = mockk(relaxed = true)
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        uploadApi = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(UploadApi::class.java)
        workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): PollStatusWorker? {
                if (workerClassName == PollStatusWorker::class.qualifiedName) {
                    return PollStatusWorker(
                        appContext,
                        workerParameters,
                        uploadApi,
                        uploadQueueRepository,
                        TestForegroundDelegate(appContext),
                        NoopUploadSummaryStarter,
                    )
                }
                return null
            }
        }
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        WorkManagerTestInitHelper.closeWorkDatabase(context)
    }

    @Test
    fun queuedStatusRetriesAndHonorsRetryAfter() = runBlocking {
        val file = createTempFile()
        val inputData = pollInputData(file)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Retry-After", "1")
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"queued"}""")
        )

        val worker = createWorker(inputData)
        val start = SystemClock.elapsedRealtime()
        val result = worker.doWork()
        val elapsed = SystemClock.elapsedRealtime() - start

        assertTrue(result is Retry)
        assertTrue(elapsed >= 900)

        val request = mockWebServer.takeRequest()
        assertEquals("/v1/uploads/upload-id/status", request.path)
    }

    @Test
    fun processingStatusRetriesWithoutDelay() = runBlocking {
        val file = createTempFile()
        val inputData = pollInputData(file)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"processing"}""")
        )

        val worker = createWorker(inputData)
        val start = SystemClock.elapsedRealtime()
        val result = worker.doWork()
        val elapsed = SystemClock.elapsedRealtime() - start

        assertTrue(result is Retry)
        assertTrue(elapsed < 500)
    }

    @Test
    fun doneStatusDeletesFileAndSucceeds() = runBlocking {
        val file = createTempFile()
        val inputData = pollInputData(file)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"done"}""")
        )

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Success)
        assertTrue(result.outputData.getBoolean(UploadEnqueuer.KEY_DELETED, false))
        assertEquals(
            UploadEnqueuer.STATE_UPLOADED_DELETED,
            result.outputData.getString(UploadEnqueuer.KEY_COMPLETION_STATE)
        )
        assertFalse(file.exists())
    }

    @Test
    fun doneStatusForMediaStoreRecordsAwaitingDeleteState() = runBlocking {
        val workId = UUID.randomUUID()
        ensureWorkSpec(workId)
        val inputData = Data.Builder()
            .putLong(UploadEnqueuer.KEY_ITEM_ID, 1L)
            .putString(UploadEnqueuer.KEY_UPLOAD_ID, "upload-id")
            .putString(UploadEnqueuer.KEY_URI, mediaStoreUri())
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, "photo.jpg")
            .build()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"done"}""")
        )

        val worker = createWorker(inputData, workId)
        val result = worker.doWork()

        assertTrue(result is Success)
        assertFalse(result.outputData.getBoolean(UploadEnqueuer.KEY_DELETED, true))
        assertEquals(
            UploadEnqueuer.STATE_UPLOADED_AWAITING_DELETE,
            result.outputData.getString(UploadEnqueuer.KEY_COMPLETION_STATE)
        )

        val progress = awaitProgress(workId) { data ->
            data.getString(UploadEnqueuer.KEY_COMPLETION_STATE) == UploadEnqueuer.STATE_UPLOADED_AWAITING_DELETE
        }
        assertEquals(
            UploadEnqueuer.STATE_UPLOADED_AWAITING_DELETE,
            progress.getString(UploadEnqueuer.KEY_COMPLETION_STATE)
        )
    }

    @Test
    fun failedStatusFailsWork() = runBlocking {
        val file = createTempFile()
        val inputData = pollInputData(file)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"failed","error":"boom"}""")
        )

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Failure)
    }

    @Test
    fun processedFallbackWithoutStatusSucceeds() = runBlocking {
        val file = createTempFile()
        val inputData = pollInputData(file)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"processed":true}""")
        )

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Success)
    }

    @Test
    fun notFoundFails() = runBlocking {
        val file = createTempFile()
        val inputData = pollInputData(file)

        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Failure)
    }

    @Test
    fun tooManyRequestsRetries() = runBlocking {
        val file = createTempFile()
        val inputData = pollInputData(file)

        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Retry)
    }

    @Test
    fun serverErrorRetries() = runBlocking {
        val file = createTempFile()
        val inputData = pollInputData(file)

        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Retry)
    }

    @Test
    fun networkFailureRetriesWithNetworkErrorKind() = runBlocking {
        val previousFactory = workerFactory
        val failingApi = object : UploadApi {
            override suspend fun upload(
                idempotencyKey: String,
                file: okhttp3.MultipartBody.Part,
                contentSha256Part: okhttp3.RequestBody,
                mime: okhttp3.RequestBody,
                size: okhttp3.RequestBody,
                exifDate: okhttp3.RequestBody?,
                originalRelpath: okhttp3.RequestBody?,
            ) = throw UnsupportedOperationException()

            override suspend fun getStatus(uploadId: String): retrofit2.Response<com.kotopogoda.uploader.core.network.api.UploadStatusDto> {
                throw UnknownHostException("dns")
            }
        }
        workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): PollStatusWorker? {
                if (workerClassName == PollStatusWorker::class.qualifiedName) {
                    return PollStatusWorker(
                        appContext,
                        workerParameters,
                        failingApi,
                        uploadQueueRepository,
                        TestForegroundDelegate(appContext),
                        NoopUploadSummaryStarter,
                    )
                }
                return null
            }
        }

        try {
            val file = createTempFile()
            val inputData = pollInputData(file)

            val worker = createWorker(inputData)
            val result = worker.doWork()

            assertTrue(result is Retry)
            val progress = worker.progress.get(1, TimeUnit.SECONDS)
            assertEquals(UploadErrorKind.NETWORK.rawValue, progress.getString(UploadEnqueuer.KEY_ERROR_KIND))
        } finally {
            workerFactory = previousFactory
        }
    }

    private fun createWorker(inputData: Data): PollStatusWorker {
        return createWorker(inputData, UUID.randomUUID())
    }

    private fun createWorker(inputData: Data, id: UUID): PollStatusWorker {
        return TestListenableWorkerBuilder<PollStatusWorker>(context)
            .setWorkerFactory(workerFactory)
            .setInputData(inputData)
            .setForegroundUpdater(ForegroundUpdater { _, _ -> })
            .setId(id)
            .build() as PollStatusWorker
    }

    private fun pollInputData(file: File): Data {
        return Data.Builder()
            .putLong(UploadEnqueuer.KEY_ITEM_ID, 1L)
            .putString(UploadEnqueuer.KEY_UPLOAD_ID, "upload-id")
            .putString(UploadEnqueuer.KEY_URI, Uri.fromFile(file).toString())
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, "photo.jpg")
            .build()
    }

    private fun ensureWorkSpec(id: UUID) {
        val request = OneTimeWorkRequestBuilder<NoopWorker>()
            .setId(id)
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        WorkManager.getInstance(context).enqueue(request).result.get()
    }

    private fun awaitProgress(id: UUID, predicate: (Data) -> Boolean): Data {
        val start = SystemClock.elapsedRealtime()
        val workManager = WorkManager.getInstance(context)
        var progress = Data.EMPTY
        while (SystemClock.elapsedRealtime() - start < 5_000) {
            val workInfo = workManager.getWorkInfoById(id).get()
            progress = workInfo?.progress ?: Data.EMPTY
            if (predicate(progress)) {
                return progress
            }
            SystemClock.sleep(10)
        }
        return progress
    }

    private fun mediaStoreUri(): String {
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            .buildUpon()
            .appendPath("123")
            .build()
            .toString()
    }

    private class NoopWorker(appContext: Context, params: WorkerParameters) : androidx.work.Worker(appContext, params) {
        override fun doWork(): Result = Result.success()
    }

    private fun createTempFile(): File {
        return File.createTempFile("poll", ".jpg", context.cacheDir).apply {
            writeText("pending")
            deleteOnExit()
        }
    }
}
