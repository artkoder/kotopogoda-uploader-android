package com.kotopogoda.uploader.core.network.work

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker.Result.Failure
import androidx.work.ListenableWorker.Result.Retry
import androidx.work.ListenableWorker.Result.Success
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.security.HmacInterceptor
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.security.DeviceCreds
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.text.Charsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@RunWith(RobolectricTestRunner::class)
class UploadWorkerTest {

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
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                HmacInterceptor(
                    deviceCredsStore = FakeDeviceCredsStore(
                        DeviceCreds(deviceId = "test-device", hmacKey = "secret-key")
                    )
                )
            )
            .build()
        uploadApi = Retrofit.Builder()
            .baseUrl(mockWebServer.url("/"))
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(UploadApi::class.java)
        workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): UploadWorker? {
                if (workerClassName == UploadWorker::class.qualifiedName) {
                    return UploadWorker(
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
    fun uploadAcceptedReturnsSuccessAndMultipartPayload() = runBlocking {
        val file = createTempFileWithContent("hello upload")
        val inputData = inputDataFor(file, displayName = "photo.jpg", idempotencyKey = "key-123")

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"upload_id":"abc","status":"accepted"}""")
        )

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Success)
        assertEquals("abc", result.outputData.getString(UploadEnqueuer.KEY_UPLOAD_ID))

        val request = mockWebServer.takeRequest()
        assertEquals("/v1/uploads", request.path)
        assertEquals("key-123", request.getHeader("Idempotency-Key"))
        val bodyBytes = request.body.readByteArray()
        val expectedBodySha = bodyBytes.sha256Hex()
        assertEquals(expectedBodySha, request.getHeader("X-Content-SHA256"))
        val body = String(bodyBytes, Charsets.UTF_8)
        val expectedFileSha = file.readBytes().sha256Hex()
        assertTrue(body.contains("name=\"content_sha256\""))
        assertTrue(body.contains(expectedFileSha))
        assertTrue(body.contains("name=\"mime\""))
        assertTrue(body.contains("application/octet-stream"))
        assertTrue(body.contains("name=\"size\""))
        assertTrue(body.contains(file.length().toString()))
        assertTrue(body.contains("name=\"file\"; filename=\"photo.jpg\""))
        assertTrue(body.contains("hello upload"))
    }

    @Test
    fun uploadConflictReturnsSuccess() = runBlocking {
        val file = createTempFileWithContent("same content")
        val inputData = inputDataFor(file, idempotencyKey = "conflict-key")

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"upload_id":"zzz","status":"accepted"}""")
        )

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Success)
        assertEquals("zzz", result.outputData.getString(UploadEnqueuer.KEY_UPLOAD_ID))
    }

    @Test
    fun payloadTooLargeFails() = runBlocking {
        val file = createTempFileWithContent("toolarge")
        val inputData = inputDataFor(file)

        mockWebServer.enqueue(MockResponse().setResponseCode(413))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Failure)
    }

    @Test
    fun unsupportedMediaTypeFails() = runBlocking {
        val file = createTempFileWithContent("badmime")
        val inputData = inputDataFor(file)

        mockWebServer.enqueue(MockResponse().setResponseCode(415))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Failure)
    }

    @Test
    fun tooManyRequestsRetries() = runBlocking {
        val file = createTempFileWithContent("retry")
        val inputData = inputDataFor(file)

        mockWebServer.enqueue(MockResponse().setResponseCode(429))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Retry)
    }

    @Test
    fun serverErrorRetries() = runBlocking {
        val file = createTempFileWithContent("error")
        val inputData = inputDataFor(file)

        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Retry)
    }

    private fun createWorker(inputData: Data): UploadWorker {
        return TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(workerFactory)
            .setInputData(inputData)
            .setForegroundUpdater(ForegroundUpdater { _, _ -> })
            .build() as UploadWorker
    }

    private fun inputDataFor(
        file: File,
        displayName: String = "photo.jpg",
        idempotencyKey: String = "key"
    ): Data {
        return Data.Builder()
            .putString(UploadEnqueuer.KEY_URI, Uri.fromFile(file).toString())
            .putString(UploadEnqueuer.KEY_IDEMPOTENCY_KEY, idempotencyKey)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
            .build()
    }

    private class FakeDeviceCredsStore(
        private val creds: DeviceCreds?,
    ) : DeviceCredsStore {
        override suspend fun save(deviceId: String, hmacKey: String) = Unit

        override suspend fun get(): DeviceCreds? = creds

        override suspend fun clear() = Unit

        override val credsFlow: Flow<DeviceCreds?> = MutableStateFlow(creds)
    }

    private fun createTempFileWithContent(content: String): File {
        return File.createTempFile("upload", ".txt", context.cacheDir).apply {
            writeText(content)
            deleteOnExit()
        }
    }

    private fun ByteArray.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(this).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
