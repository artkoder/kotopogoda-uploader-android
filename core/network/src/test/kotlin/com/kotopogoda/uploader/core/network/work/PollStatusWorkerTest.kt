package com.kotopogoda.uploader.core.network.work

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result.Failure
import androidx.work.ListenableWorker.Result.Retry
import androidx.work.ListenableWorker.Result.Success
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
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
import androidx.work.ForegroundUpdater
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PollStatusWorkerTest {

    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer
    private lateinit var uploadApi: UploadApi
    private lateinit var workerFactory: WorkerFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TestForegroundDelegate.ensureChannel(context)
        mockWebServer = MockWebServer().apply { start() }
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
        assertFalse(file.exists())
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

    private fun createWorker(inputData: Data): PollStatusWorker {
        return TestListenableWorkerBuilder<PollStatusWorker>(context)
            .setWorkerFactory(workerFactory)
            .setInputData(inputData)
            .setForegroundUpdater(ForegroundUpdater { _, _ -> })
            .build() as PollStatusWorker
    }

    private fun pollInputData(file: File): Data {
        return Data.Builder()
            .putString(UploadEnqueuer.KEY_UPLOAD_ID, "upload-id")
            .putString(UploadEnqueuer.KEY_URI, Uri.fromFile(file).toString())
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, "photo.jpg")
            .build()
    }

    private fun createTempFile(): File {
        return File.createTempFile("poll", ".jpg", context.cacheDir).apply {
            writeText("pending")
            deleteOnExit()
        }
    }
}
