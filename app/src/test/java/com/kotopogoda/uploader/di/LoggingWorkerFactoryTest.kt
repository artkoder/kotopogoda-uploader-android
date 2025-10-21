package com.kotopogoda.uploader.di

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.logging.diagnostic.AppInfo
import com.kotopogoda.uploader.core.logging.diagnostic.AppInfoProvider
import com.kotopogoda.uploader.core.logging.diagnostic.DeviceInfo
import com.kotopogoda.uploader.core.logging.diagnostic.DeviceInfoProvider
import com.kotopogoda.uploader.core.logging.diagnostic.DiagnosticContextProvider
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import timber.log.Timber

class LoggingWorkerFactoryTest {

    @MockK
    lateinit var hiltWorkerFactory: HiltWorkerFactory

    @MockK
    lateinit var workerParameters: WorkerParameters

    private val context: Context = mockk()

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        Timber.uprootAll()
        val diagnosticContextProvider = DiagnosticContextProvider(
            appInfoProvider = object : AppInfoProvider {
                override fun appInfo(): AppInfo = AppInfo(
                    applicationId = "com.example",
                    versionName = "1.0",
                    contractVersion = "v1",
                    buildType = "debug",
                )
            },
            deviceInfoProvider = object : DeviceInfoProvider {
                override fun deviceInfo(): DeviceInfo = DeviceInfo(
                    manufacturer = "Example",
                    model = "Model",
                    brand = "Brand",
                    device = "Device",
                    androidRelease = "1.0",
                    sdkInt = 33,
                )
            },
        )
        UploadLog.setDiagnosticContextProvider(diagnosticContextProvider)
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `createWorker logs when delegate throws`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        val error = IllegalStateException("boom")
        every { hiltWorkerFactory.createWorker(any(), any(), any()) } throws error

        val workerId = UUID.randomUUID()
        every { workerParameters.id } returns workerId
        every { workerParameters.tags } returns setOf("tag1", "tag2")

        val factory = LoggingWorkerFactory(hiltWorkerFactory)

        val thrown = assertThrows(IllegalStateException::class.java) {
            factory.createWorker(context, "com.example.QueueDrainWorker", workerParameters)
        }
        assertSame(error, thrown)

        val attemptLog = tree.entries.first { entry ->
            entry.message.contains("action=attempt_create")
        }
        assertTrue(attemptLog.message.contains("worker_class_name=com.example.QueueDrainWorker"))
        assertTrue(attemptLog.message.contains("work_id=$workerId"))
        assertTrue(attemptLog.message.contains("tags=tag1, tag2"))
        assertTrue(attemptLog.message.contains("session_id="))

        val errorLog = tree.entries.first { entry ->
            entry.message.contains("action=drain_worker_create_error")
        }
        assertSame(error, errorLog.throwable)
        val message = errorLog.message
        assertTrue(message.contains("worker_class_name=com.example.QueueDrainWorker"))
        assertTrue(message.contains("work_id=$workerId"))
        assertTrue(message.contains("tags=tag1, tag2"))
    }

    @Test
    fun `createWorker logs delegate success`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        val workerId = UUID.randomUUID()
        every { workerParameters.id } returns workerId
        every { workerParameters.tags } returns setOf("tag-success")

        val worker = mockk<ListenableWorker>()
        every { hiltWorkerFactory.createWorker(any(), any(), any()) } returns worker

        val factory = LoggingWorkerFactory(hiltWorkerFactory)

        val created = factory.createWorker(context, "com.example.SuccessWorker", workerParameters)

        assertSame(worker, created)

        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=attempt_create") &&
                entry.message.contains("worker_class_name=com.example.SuccessWorker")
        })

        val successLog = tree.entries.first { entry ->
            entry.message.contains("action=delegate_success")
        }
        assertTrue(successLog.message.contains("worker_class_name=com.example.SuccessWorker"))
        assertTrue(successLog.message.contains("factory=${hiltWorkerFactory.javaClass.name}"))
        assertTrue(successLog.message.contains("work_id=$workerId"))
        assertTrue(successLog.message.contains("tags=tag-success"))
    }

    @Test
    fun `createWorker returns fallback when delegate returns null`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        every { hiltWorkerFactory.createWorker(any(), any(), any()) } returns null

        val workerId = UUID.randomUUID()
        every { workerParameters.id } returns workerId
        every { workerParameters.tags } returns setOf("tag3")

        val fallbackWorker = mockk<ListenableWorker>()
        var fallbackCalled = false
        val factory = object : LoggingWorkerFactory(hiltWorkerFactory) {
            override fun fallbackCreateWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                fallbackCalled = true
                return fallbackWorker
            }
        }

        val worker = factory.createWorker(context, "com.example.LegacyWorker", workerParameters)

        assertSame(fallbackWorker, worker)
        assertTrue(fallbackCalled)

        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=attempt_create") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker")
        })

        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=hilt_null") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker") &&
                entry.message.contains("work_id=$workerId") &&
                entry.message.contains("tags=tag3")
        })

        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=create_null") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker") &&
                entry.message.contains("work_id=$workerId") &&
                entry.message.contains("tags=tag3")
        })

        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=fallback_start") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker")
        })

        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=fallback") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker") &&
                entry.message.contains("work_id=$workerId") &&
                entry.message.contains("tags=tag3") &&
                !entry.message.contains("result=null")
        })
    }

    @Test
    fun `createWorker logs and returns null when fallback returns null`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        every { hiltWorkerFactory.createWorker(any(), any(), any()) } returns null

        val workerId = UUID.randomUUID()
        every { workerParameters.id } returns workerId
        every { workerParameters.tags } returns setOf("tag3")

        var fallbackCalled = false
        val factory = object : LoggingWorkerFactory(hiltWorkerFactory) {
            override fun fallbackCreateWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                fallbackCalled = true
                return null
            }
        }

        val worker = factory.createWorker(context, "com.example.LegacyWorker", workerParameters)

        assertTrue(worker == null)
        assertTrue(fallbackCalled)

        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=attempt_create") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker")
        })
        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=hilt_null") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker")
        })
        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=create_null") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker")
        })
        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=fallback_start") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker")
        })
        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=fallback") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker") &&
                entry.message.contains("result=null")
        })
        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=fallback_null") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker")
        })
    }

    @Test
    fun `createWorker swallows fallback errors`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        every { hiltWorkerFactory.createWorker(any(), any(), any()) } returns null

        val workerId = UUID.randomUUID()
        every { workerParameters.id } returns workerId
        every { workerParameters.tags } returns setOf("tag4")

        val fallbackError = IllegalStateException("fallback boom")
        val factory = object : LoggingWorkerFactory(hiltWorkerFactory) {
            override fun fallbackCreateWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                throw fallbackError
            }
        }

        val worker = factory.createWorker(context, "com.example.LegacyWorker", workerParameters)

        assertTrue(worker == null)

        val fallbackErrorLog = tree.entries.firstOrNull { entry ->
            entry.message.contains("action=fallback_error")
        }
        assertTrue(fallbackErrorLog != null)
        assertSame(fallbackError, fallbackErrorLog!!.throwable)
        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=fallback_start") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker")
        })
        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=fallback") &&
                entry.message.contains("result=null")
        })
        assertTrue(tree.entries.any { entry ->
            entry.message.contains("action=fallback_null") &&
                entry.message.contains("worker_class_name=com.example.LegacyWorker")
        })
    }

    private class RecordingTree : Timber.Tree() {
        data class Entry(
            val priority: Int,
            val tag: String?,
            val message: String,
            val throwable: Throwable?,
        )

        val entries: MutableList<Entry> = mutableListOf()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += Entry(priority, tag, message, t)
        }
    }
}
