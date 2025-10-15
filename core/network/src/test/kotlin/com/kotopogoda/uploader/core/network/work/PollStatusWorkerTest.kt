package com.kotopogoda.uploader.core.network.work

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.os.SystemClock
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
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.UUID
import java.util.concurrent.TimeUnit
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
import androidx.work.Configuration
import kotlinx.coroutines.runBlocking
import org.robolectric.Robolectric
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class PollStatusWorkerTest {

    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer
    private lateinit var uploadApi: UploadApi
    private lateinit var workerFactory: WorkerFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val configuration = Configuration.Builder()
            .setMinimumLoggingLevel(Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
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

    @Test
    fun pendingDeleteConfirmedFlowCompletesWork() = runBlocking {
        val workId = UUID.randomUUID()
        ensureWorkSpec(workId)
        val pendingIntentBytes = createSerializedPendingIntent()
        val intent = helperIntent(workId, pendingIntentBytes)
        val controller = Robolectric.buildActivity(TestDeleteRequestHelperActivity::class.java, intent).setup()
        assertEquals(DeleteRequestContract.REQUEST_CODE_DELETE, controller.get().lastRequestCode)
        val progressAfterLaunch = awaitProgress(workId) { progress ->
            progress.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS) == DeleteRequestContract.STATUS_PENDING
        }
        assertEquals(DeleteRequestContract.STATUS_PENDING, progressAfterLaunch.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS))
        val storedBytes = progressAfterLaunch.getByteArray(DeleteRequestContract.KEY_PENDING_DELETE_INTENT)
        assertNotNull(storedBytes)
        assertTrue(storedBytes.isNotEmpty())

        controller.get().onActivityResult(
            DeleteRequestContract.REQUEST_CODE_DELETE,
            Activity.RESULT_OK,
            null
        )

        val progressAfterResult = awaitProgress(workId) { progress ->
            progress.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS) == DeleteRequestContract.STATUS_CONFIRMED
        }
        assertEquals(DeleteRequestContract.STATUS_CONFIRMED, progressAfterResult.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS))
        controller.pause().stop().destroy()

        val inputData = Data.Builder()
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
        assertTrue(result.outputData.getBoolean(UploadEnqueuer.KEY_DELETED, false))

        val clearedProgress = awaitProgress(workId) { progress ->
            progress.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS) == DeleteRequestContract.STATUS_NONE
        }
        assertEquals(DeleteRequestContract.STATUS_NONE, clearedProgress.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS))
        assertEquals(0, clearedProgress.getByteArray(DeleteRequestContract.KEY_PENDING_DELETE_INTENT)?.size)
    }

    @Test
    fun pendingDeleteDeclinedFlowCompletesWithoutDeletion() = runBlocking {
        val workId = UUID.randomUUID()
        ensureWorkSpec(workId)
        val pendingIntentBytes = createSerializedPendingIntent()
        val intent = helperIntent(workId, pendingIntentBytes)
        val controller = Robolectric.buildActivity(TestDeleteRequestHelperActivity::class.java, intent).setup()
        assertEquals(DeleteRequestContract.REQUEST_CODE_DELETE, controller.get().lastRequestCode)
        awaitProgress(workId) { progress ->
            progress.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS) == DeleteRequestContract.STATUS_PENDING
        }

        controller.get().onActivityResult(
            DeleteRequestContract.REQUEST_CODE_DELETE,
            Activity.RESULT_CANCELED,
            null
        )

        val progressAfterResult = awaitProgress(workId) { progress ->
            progress.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS) == DeleteRequestContract.STATUS_DECLINED
        }
        assertEquals(DeleteRequestContract.STATUS_DECLINED, progressAfterResult.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS))
        controller.pause().stop().destroy()

        val inputData = Data.Builder()
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

        val clearedProgress = awaitProgress(workId) { progress ->
            progress.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS) == DeleteRequestContract.STATUS_NONE
        }
        assertEquals(DeleteRequestContract.STATUS_NONE, clearedProgress.getString(DeleteRequestContract.KEY_PENDING_DELETE_STATUS))
        assertEquals(0, clearedProgress.getByteArray(DeleteRequestContract.KEY_PENDING_DELETE_INTENT)?.size)
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

    private fun helperIntent(workId: UUID, bytes: ByteArray): Intent {
        return Intent(ApplicationProvider.getApplicationContext(), TestDeleteRequestHelperActivity::class.java)
            .putExtra(DeleteRequestContract.EXTRA_WORK_ID, workId.toString())
            .putExtra(DeleteRequestContract.EXTRA_PENDING_INTENT, bytes)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun createSerializedPendingIntent(): ByteArray {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, DummyActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return PendingIntentSerializer.serialize(pendingIntent)
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

    private class TestDeleteRequestHelperActivity : DeleteRequestHelperActivity() {
        var lastRequestCode: Int? = null

        override fun startIntentSenderForResult(
            intent: android.content.IntentSender?,
            requestCode: Int,
            fillInIntent: Intent?,
            flagsMask: Int,
            flagsValues: Int,
            extraFlags: Int,
            options: android.os.Bundle?
        ) {
            lastRequestCode = requestCode
        }
    }

    private class DummyActivity : Activity()

    private fun createTempFile(): File {
        return File.createTempFile("poll", ".jpg", context.cacheDir).apply {
            writeText("pending")
            deleteOnExit()
        }
    }
}
