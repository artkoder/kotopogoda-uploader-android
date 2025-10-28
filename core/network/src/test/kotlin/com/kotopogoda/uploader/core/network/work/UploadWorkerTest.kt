package com.kotopogoda.uploader.core.network.work

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.Observer
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker.Result.Failure
import androidx.work.ListenableWorker.Result.Retry
import androidx.work.ListenableWorker.Result.Success
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkerFactory
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.kotopogoda.uploader.core.data.upload.UploadQueueRepository
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.security.HmacInterceptor
import com.kotopogoda.uploader.core.network.upload.UploadEnqueuer
import com.kotopogoda.uploader.core.network.upload.UploadConstraintsProvider
import com.kotopogoda.uploader.core.network.upload.UploadForegroundDelegate
import com.kotopogoda.uploader.core.network.upload.UploadForegroundKind
import com.kotopogoda.uploader.core.network.upload.UploadTags
import com.kotopogoda.uploader.core.network.upload.UploadWorkKind
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import com.kotopogoda.uploader.core.work.UploadErrorKind
import com.kotopogoda.uploader.core.security.DeviceCreds
import com.kotopogoda.uploader.core.security.DeviceCredsStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.kotopogoda.uploader.core.work.WorkManagerProvider

@RunWith(RobolectricTestRunner::class)
class UploadWorkerTest {

    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer
    private lateinit var uploadApi: UploadApi
    private lateinit var workerFactory: WorkerFactory
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var workManager: WorkManager
    private lateinit var workManagerProvider: WorkManagerProvider
    private lateinit var constraintsProvider: UploadConstraintsProvider
    private lateinit var constraintsState: MutableStateFlow<Constraints?>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TestForegroundDelegate.ensureChannel(context)
        mockWebServer = MockWebServer().apply { start() }
        uploadQueueRepository = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        workManagerProvider = WorkManagerProvider { workManager }
        constraintsProvider = mockk(relaxed = true)
        constraintsState = MutableStateFlow(Constraints.Builder().build())
        every { constraintsProvider.constraintsState } returns constraintsState
        coEvery { constraintsProvider.awaitConstraints() } answers { constraintsState.value ?: Constraints.Builder().build() }
        every { constraintsProvider.buildConstraints() } answers { constraintsState.value ?: Constraints.Builder().build() }
        every { constraintsProvider.shouldUseExpeditedWork() } returns true
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
                        uploadQueueRepository,
                        TestForegroundDelegate(appContext),
                        NoopUploadSummaryStarter,
                        workManagerProvider,
                        constraintsProvider,
                    )
                }
                return null
            }
        }
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        ShadowContentResolver.reset()
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
        assertEquals(file.length(), result.outputData.getLong(UploadEnqueuer.KEY_BYTES_SENT, -1))
        assertEquals(file.length(), result.outputData.getLong(UploadEnqueuer.KEY_TOTAL_BYTES, -1))

        val request = mockWebServer.takeRequest()
        assertEquals("/v1/uploads", request.path)
        assertEquals("key-123", request.getHeader("Idempotency-Key"))
        val bodyBytes = request.body.readByteArray()
        val body = String(bodyBytes, Charsets.UTF_8)
        val expectedFileSha = file.readBytes().sha256Hex()
        val expectedRequestSha = bodyBytes.sha256Hex()
        assertEquals(expectedRequestSha, request.getHeader("X-Content-SHA256"))
        val boundary = request.getHeader("Content-Type")?.substringAfter("boundary=")?.trim()
        requireNotNull(boundary) { "Multipart boundary missing" }
        val contentShaPart = body.findMultipartValue(boundary, "content_sha256")
        assertEquals(expectedFileSha, contentShaPart)
        assertTrue(body.contains("name=\"mime\""))
        assertTrue(body.contains("application/octet-stream"))
        assertTrue(body.contains("name=\"size\""))
        assertTrue(body.contains(file.length().toString()))
        assertTrue(body.contains("name=\"file\"; filename=\"photo.jpg\""))
        assertTrue(body.contains("hello upload"))
    }

    @Test
    fun uploadProgressNeverRegresses() = runBlocking {
        val fileSize = 512 * 1024
        val fileBytes = ByteArray(fileSize) { index -> (index % 251).toByte() }
        val file = File.createTempFile("monotonic", ".bin", context.cacheDir).apply {
            writeBytes(fileBytes)
            deleteOnExit()
        }
        val inputData = inputDataFor(file, displayName = "progress.bin", idempotencyKey = "progress-key")

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"upload_id":"progress","status":"accepted"}""")
        )

        val worker = createWorker(inputData)
        val observedProgress = mutableListOf<Int>()
        val observer = Observer<Data> { data ->
            if (data.hasKeyWithValueOfType<Int>(UploadEnqueuer.KEY_PROGRESS)) {
                observedProgress += data.getInt(UploadEnqueuer.KEY_PROGRESS, -1)
            }
        }

        worker.progress.observeForever(observer)
        try {
            val result = worker.doWork()
            assertTrue(result is Success)
        } finally {
            worker.progress.removeObserver(observer)
        }

        shadowOf(Looper.getMainLooper()).idle()

        val determinateProgress = observedProgress.filter { it >= 0 }
        assertTrue(determinateProgress.isNotEmpty(), "Expected determinate progress updates, got $determinateProgress")
        determinateProgress.zipWithNext().forEach { (previous, current) ->
            assertTrue(current >= previous, "Progress regressed from $previous to $current. All updates: $determinateProgress")
        }
        assertEquals(100, determinateProgress.last())
    }

    @Test
    fun streamingPayloadReadsInChunksAndComputesDigest() = runBlocking {
        val authority = "com.kotopogoda.test.stream"
        val uri = Uri.parse("content://$authority/items/1")
        val data = ByteArray(STREAMING_TEST_SIZE) { index -> (index % 251).toByte() }
        val streamFactory = TrackingInputStreamFactory(uri, data, context.contentResolver)
        streamFactory.prepare(streamCount = 3)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"upload_id":"stream","status":"accepted"}""")
        )

        val worker = createWorker(inputDataForUri(uri, displayName = "stream.bin", idempotencyKey = "stream-key"))
        val result = worker.doWork()

        assertTrue(result is Success)
        val outputData = result.outputData
        assertEquals(STREAMING_TEST_SIZE.toLong(), outputData.getLong(UploadEnqueuer.KEY_BYTES_SENT, -1))
        assertEquals(STREAMING_TEST_SIZE.toLong(), outputData.getLong(UploadEnqueuer.KEY_TOTAL_BYTES, -1))

        val request = mockWebServer.takeRequest()
        val bodyBytes = request.body.readByteArray()
        val bodyString = String(bodyBytes, Charsets.UTF_8)
        val expectedFileSha = data.sha256Hex()
        val expectedRequestSha = bodyBytes.sha256Hex()
        assertEquals(expectedRequestSha, request.headers["X-Content-SHA256"])
        val boundary = request.getHeader("Content-Type")?.substringAfter("boundary=")?.trim()
        requireNotNull(boundary) { "Multipart boundary missing" }
        val contentShaPart = bodyString.findMultipartValue(boundary, "content_sha256")
        assertEquals(expectedFileSha, contentShaPart)
        assertTrue(bodyString.contains(expectedFileSha))

        val readHistory = streamFactory.readHistory
        assertEquals(3, readHistory.size)
        readHistory.forEach { reads ->
            assertEquals(STREAMING_TEST_SIZE, reads.sum())
            assertTrue(reads.size > 1, "Expected multiple chunk reads, got $reads")
            assertTrue((reads.maxOrNull() ?: 0) < STREAMING_TEST_SIZE, "Chunks should be smaller than the whole payload")
        }
    }

    @Test
    fun uploadAcceptedEnqueuesPollWorkerWithExpectedInputAndTags() = runBlocking {
        val file = createTempFileWithContent("poll content")
        val inputData = inputDataFor(file, displayName = "poll.jpg", idempotencyKey = "poll-key")
        val expectedConstraints = Constraints.Builder()
            .setRequiresCharging(true)
            .build()
        constraintsState.value = expectedConstraints
        coEvery { constraintsProvider.awaitConstraints() } returns expectedConstraints

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"upload_id":"poll-id","status":"accepted"}""")
        )

        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(any(), any(), capture(requestSlot))
        } returns mockk<Operation>(relaxed = true)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Success)
        val expectedUniqueName = UploadEnqueuer.uniqueNameForUri(Uri.fromFile(file))
        verify(exactly = 1) {
            workManager.enqueueUniqueWork("$expectedUniqueName:poll", ExistingWorkPolicy.REPLACE, any())
        }

        val pollRequest = requestSlot.captured
        val pollInput = pollRequest.workSpec.input
        assertEquals(1L, pollInput.getLong(UploadEnqueuer.KEY_ITEM_ID, -1))
        assertEquals("poll-id", pollInput.getString(UploadEnqueuer.KEY_UPLOAD_ID))
        assertEquals(Uri.fromFile(file).toString(), pollInput.getString(UploadEnqueuer.KEY_URI))
        assertEquals("poll.jpg", pollInput.getString(UploadEnqueuer.KEY_DISPLAY_NAME))

        val expectedTags = setOf(
            UploadTags.TAG_POLL,
            UploadTags.uniqueTag(expectedUniqueName),
            UploadTags.uriTag(Uri.fromFile(file).toString()),
            UploadTags.displayNameTag("poll.jpg"),
            UploadTags.keyTag("poll-key"),
            UploadTags.kindTag(UploadWorkKind.POLL),
        )
        assertTrue(pollRequest.tags.containsAll(expectedTags))
        assertEquals(expectedConstraints, pollRequest.workSpec.constraints)
        assertEquals(false, pollRequest.workSpec.expedited)
    }

    @Test
    fun uploadAcceptedEnqueuesPollWorkerWithExpeditedPolicyWhenAllowed() = runBlocking {
        val file = createTempFileWithContent("poll expedited")
        val inputData = inputDataFor(file, displayName = "poll-exp.jpg", idempotencyKey = "poll-exp-key")
        every { constraintsProvider.shouldUseExpeditedWork() } returns true
        coEvery { constraintsProvider.awaitConstraints() } returns constraintsState.value ?: Constraints.Builder().build()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"upload_id":"poll-exp-id","status":"accepted"}""")
        )

        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(any(), any(), capture(requestSlot))
        } returns mockk(relaxed = true)

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Success)
        val pollRequest = requestSlot.captured
        assertEquals(true, pollRequest.workSpec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, pollRequest.workSpec.outOfQuotaPolicy)
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
        val outputData = (result as Failure).outputData
        assertEquals(UploadErrorKind.HTTP.rawValue, outputData.getString(UploadEnqueuer.KEY_ERROR_KIND))
        assertEquals(413, outputData.getInt(UploadEnqueuer.KEY_HTTP_CODE, -1))
    }

    @Test
    fun unsupportedMediaTypeFails() = runBlocking {
        val file = createTempFileWithContent("badmime")
        val inputData = inputDataFor(file)

        mockWebServer.enqueue(MockResponse().setResponseCode(415))

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Failure)
        val outputData = (result as Failure).outputData
        assertEquals(UploadErrorKind.HTTP.rawValue, outputData.getString(UploadEnqueuer.KEY_ERROR_KIND))
        assertEquals(415, outputData.getInt(UploadEnqueuer.KEY_HTTP_CODE, -1))
    }

    @Test
    fun unauthorizedFailsWithAuthErrorAndMessage() = runBlocking {
        val file = createTempFileWithContent("unauthorized")
        val inputData = inputDataFor(file)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"unauthorized","message":"Token expired"}""")
        )

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Failure)
        val outputData = (result as Failure).outputData
        assertEquals(UploadErrorKind.AUTH.rawValue, outputData.getString(UploadEnqueuer.KEY_ERROR_KIND))
        assertEquals(401, outputData.getInt(UploadEnqueuer.KEY_HTTP_CODE, -1))
        assertEquals("Token expired", outputData.getString(UploadEnqueuer.KEY_ERROR_MESSAGE))
        coVerify(exactly = 1) {
            uploadQueueRepository.markFailed(
                id = 1L,
                errorKind = UploadErrorKind.AUTH,
                httpCode = 401,
                requeue = false,
                errorMessage = "Token expired",
            )
        }
    }

    @Test
    fun forbiddenFailsWithAuthErrorAndFallbackMessage() = runBlocking {
        val file = createTempFileWithContent("forbidden")
        val inputData = inputDataFor(file)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"forbidden"}""")
        )

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Failure)
        val outputData = (result as Failure).outputData
        assertEquals(UploadErrorKind.AUTH.rawValue, outputData.getString(UploadEnqueuer.KEY_ERROR_KIND))
        assertEquals(403, outputData.getInt(UploadEnqueuer.KEY_HTTP_CODE, -1))
        assertEquals("forbidden", outputData.getString(UploadEnqueuer.KEY_ERROR_MESSAGE))
        coVerify(exactly = 1) {
            uploadQueueRepository.markFailed(
                id = 1L,
                errorKind = UploadErrorKind.AUTH,
                httpCode = 403,
                requeue = false,
                errorMessage = "forbidden",
            )
        }
    }

    @Test
    fun httpErrorMessageIsPropagated() = runBlocking {
        val file = createTempFileWithContent("server-error")
        val inputData = inputDataFor(file)

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"invalid_header","message":"X-Timestamp must be an integer"}""")
        )

        val worker = createWorker(inputData)
        val result = worker.doWork()

        assertTrue(result is Failure)
        val outputData = (result as Failure).outputData
        assertEquals(
            "X-Timestamp must be an integer",
            outputData.getString(UploadEnqueuer.KEY_ERROR_MESSAGE)
        )
        coVerify {
            uploadQueueRepository.markFailed(
                id = 1L,
                errorKind = UploadErrorKind.HTTP,
                httpCode = 422,
                requeue = false,
                errorMessage = "X-Timestamp must be an integer",
            )
        }
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

    @Test
    fun networkFailureRetriesWithNetworkErrorKind() = runBlocking {
        val previousFactory = workerFactory
        val failingApi = object : UploadApi {
            override suspend fun upload(
                idempotencyKey: String,
                contentSha256Header: String,
                file: okhttp3.MultipartBody.Part,
                contentSha256Part: okhttp3.RequestBody,
                mime: okhttp3.RequestBody,
                size: okhttp3.RequestBody,
                exifDate: okhttp3.RequestBody?,
                originalRelpath: okhttp3.RequestBody?,
            ): retrofit2.Response<com.kotopogoda.uploader.core.network.api.UploadAcceptedDto> {
                throw UnknownHostException("dns")
            }

            override suspend fun getStatus(uploadId: String) = throw UnsupportedOperationException()
        }
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
                        failingApi,
                        uploadQueueRepository,
                        TestForegroundDelegate(appContext),
                        NoopUploadSummaryStarter,
                        workManagerProvider,
                        constraintsProvider,
                    )
                }
                return null
            }
        }

        try {
            val file = createTempFileWithContent("network")
            val inputData = inputDataFor(file)

            val worker = createWorker(inputData)
            val result = worker.doWork()

            assertTrue(result is Retry)
            val progress = worker.progress.get(1, TimeUnit.SECONDS)
            assertEquals(UploadErrorKind.NETWORK.rawValue, progress.getString(UploadEnqueuer.KEY_ERROR_KIND))
        } finally {
            workerFactory = previousFactory
        }
    }

    @Test
    fun summaryStarterExceptionDoesNotCrashWorker() = runBlocking {
        val previousFactory = workerFactory
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
                        uploadQueueRepository,
                        TestForegroundDelegate(appContext),
                        ThrowingUploadSummaryStarter,
                        workManagerProvider,
                        constraintsProvider,
                    )
                }
                return null
            }
        }

        try {
            val file = createTempFileWithContent("summary")
            val inputData = inputDataFor(file)
            mockWebServer.enqueue(
                MockResponse()
                    .setResponseCode(202)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"upload_id":"summary","status":"accepted"}""")
            )

            val worker = createWorker(inputData)
            val result = worker.doWork()

            assertTrue(result is Success)
        } finally {
            workerFactory = previousFactory
        }
    }

    @Test
    fun foregroundUpdaterExceptionDoesNotCrashOnRetry() = runBlocking {
        val previousFactory = workerFactory
        val failingApi = object : UploadApi {
            override suspend fun upload(
                idempotencyKey: String,
                contentSha256Header: String,
                file: okhttp3.MultipartBody.Part,
                contentSha256Part: okhttp3.RequestBody,
                mime: okhttp3.RequestBody,
                size: okhttp3.RequestBody,
                exifDate: okhttp3.RequestBody?,
                originalRelpath: okhttp3.RequestBody?,
            ): retrofit2.Response<com.kotopogoda.uploader.core.network.api.UploadAcceptedDto> {
                throw UnknownHostException("fgs")
            }

            override suspend fun getStatus(uploadId: String) = throw UnsupportedOperationException()
        }
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
                        failingApi,
                        uploadQueueRepository,
                        TestForegroundDelegate(appContext),
                        NoopUploadSummaryStarter,
                        workManagerProvider,
                        constraintsProvider,
                    )
                }
                return null
            }
        }

        try {
            val file = createTempFileWithContent("retry-guard")
            val inputData = inputDataFor(file)

            val worker = createWorker(
                inputData = inputData,
                foregroundUpdater = ForegroundUpdater { _, _ ->
                    throw IllegalStateException("fg update forbidden")
                }
            )
            val result = worker.doWork()

            assertTrue(result is Retry)
        } finally {
            workerFactory = previousFactory
        }
    }

    @Test
    fun foregroundDelegateExceptionDoesNotCrashOnFailure() = runBlocking {
        val previousFactory = workerFactory
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
                        uploadQueueRepository,
                        ThrowingForegroundDelegate,
                        NoopUploadSummaryStarter,
                        workManagerProvider,
                        constraintsProvider,
                    )
                }
                return null
            }
        }

        try {
            val file = createTempFileWithContent("fail-guard")
            val inputData = inputDataFor(file)
            mockWebServer.enqueue(MockResponse().setResponseCode(413))

            val worker = createWorker(inputData)
            val result = worker.doWork()

            assertTrue(result is Failure)
        } finally {
            workerFactory = previousFactory
        }
    }

    @Test
    fun securityExceptionFailsWithoutRetry() = runBlocking {
        val authority = "com.kotopogoda.test.secure"
        val uri = Uri.parse("content://$authority/items/1")
        ShadowContentResolver.registerProviderInternal(authority, ThrowingSecurityProvider(SecurityException("denied")))

        val inputData = inputDataForUri(uri)
        val worker = createWorker(inputData)

        val result = worker.doWork()

        assertTrue(result is Failure)
        val outputData = (result as Failure).outputData
        assertEquals(UploadErrorKind.IO.rawValue, outputData.getString(UploadEnqueuer.KEY_ERROR_KIND))
    }

    @Test
    fun fileNotFoundExceptionFailsWithoutRetry() = runBlocking {
        val authority = "com.kotopogoda.test.missing"
        val uri = Uri.parse("content://$authority/items/1")
        ShadowContentResolver.registerProviderInternal(authority, ThrowingFileNotFoundProvider)

        val inputData = inputDataForUri(uri)
        val worker = createWorker(inputData)

        val result = worker.doWork()

        assertTrue(result is Failure)
        val outputData = (result as Failure).outputData
        assertEquals(UploadErrorKind.IO.rawValue, outputData.getString(UploadEnqueuer.KEY_ERROR_KIND))
    }

    private fun createWorker(
        inputData: Data,
        factory: WorkerFactory = workerFactory,
        foregroundUpdater: ForegroundUpdater = ForegroundUpdater { _, _ -> },
    ): UploadWorker {
        return TestListenableWorkerBuilder<UploadWorker>(context)
            .setWorkerFactory(factory)
            .setInputData(inputData)
            .setForegroundUpdater(foregroundUpdater)
            .build() as UploadWorker
    }

    private fun inputDataFor(
        file: File,
        displayName: String = "photo.jpg",
        idempotencyKey: String = "key"
    ): Data {
        return Data.Builder()
            .putLong(UploadEnqueuer.KEY_ITEM_ID, 1L)
            .putString(UploadEnqueuer.KEY_URI, Uri.fromFile(file).toString())
            .putString(UploadEnqueuer.KEY_IDEMPOTENCY_KEY, idempotencyKey)
            .putString(UploadEnqueuer.KEY_DISPLAY_NAME, displayName)
            .build()
    }

    private fun inputDataForUri(
        uri: Uri,
        displayName: String = "photo.jpg",
        idempotencyKey: String = "key",
    ): Data {
        return Data.Builder()
            .putLong(UploadEnqueuer.KEY_ITEM_ID, 1L)
            .putString(UploadEnqueuer.KEY_URI, uri.toString())
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

    private fun String.findMultipartValue(boundary: String, name: String): String? {
        val delimiter = "--$boundary"
        return split(delimiter)
            .asSequence()
            .map { it.trim('\r', '\n') }
            .firstNotNullOfOrNull { part ->
                if (part.isEmpty() || part == "--") return@firstNotNullOfOrNull null
                if (!part.contains("name=\"$name\"")) return@firstNotNullOfOrNull null
                val lines = part.split("\r\n")
                val blankIndex = lines.indexOf("")
                if (blankIndex == -1 || blankIndex + 1 >= lines.size) return@firstNotNullOfOrNull null
                lines.drop(blankIndex + 1)
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("--") }
                    ?.trimEnd('\r', '\n')
            }
    }

    private class TrackingInputStreamFactory(
        private val uri: Uri,
        private val data: ByteArray,
        private val resolver: ContentResolver,
    ) {
        val readHistory = mutableListOf<List<Int>>()
        private var remainingStreams: Int = 0

        fun prepare(streamCount: Int) {
            readHistory.clear()
            remainingStreams = streamCount
            registerNextStream()
        }

        private fun registerNextStream() {
            if (remainingStreams <= 0) return
            shadowOf(resolver).registerInputStream(uri, createStream())
        }

        private fun createStream(): InputStream {
            val reads = mutableListOf<Int>()
            readHistory += reads
            var position = 0
            var closed = false
            return object : InputStream() {
                override fun read(): Int {
                    val single = ByteArray(1)
                    val read = read(single, 0, 1)
                    return if (read == -1) -1 else single[0].toInt() and 0xFF
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= data.size) {
                        return -1
                    }
                    val toRead = min(length, data.size - position)
                    System.arraycopy(data, position, buffer, offset, toRead)
                    reads.add(toRead)
                    position += toRead
                    return toRead
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    remainingStreams -= 1
                    registerNextStream()
                }
            }
        }
    }

    private companion object {
        const val STREAMING_TEST_SIZE = 256 * 1024 + 123
    }

    private object ThrowingUploadSummaryStarter : UploadSummaryStarter {
        override fun ensureRunning() {
            throw IllegalStateException("summary service start blocked")
        }
    }

    private object ThrowingForegroundDelegate : UploadForegroundDelegate {
        override fun create(
            displayName: String,
            progress: Int,
            workId: UUID,
            kind: UploadForegroundKind
        ): ForegroundInfo {
            throw IllegalStateException("foreground notification denied")
        }
    }

    private class ThrowingSecurityProvider(
        private val throwable: SecurityException,
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): android.database.Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor {
            throw throwable
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            throw FileNotFoundException("Denied")
        }
    }

    private object ThrowingFileNotFoundProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?
        ): android.database.Cursor? = null

        override fun getType(uri: Uri): String? = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?
        ): Int = 0

        override fun openAssetFile(uri: Uri, mode: String): android.content.res.AssetFileDescriptor {
            throw FileNotFoundException("Missing")
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            throw FileNotFoundException("Missing")
        }
    }
}
