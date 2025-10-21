package com.kotopogoda.uploader.di

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
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

        val log = tree.entries.single()
        assertSame(error, log.throwable)
        val message = log.message
        assertTrue(message.contains("action=drain_worker_create_error"))
        assertTrue(message.contains("worker_class_name=com.example.QueueDrainWorker"))
        assertTrue(message.contains("work_id=$workerId"))
        assertTrue(message.contains("tags=tag1, tag2"))
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

        val createNullLog = tree.entries.firstOrNull { entry ->
            entry.message.contains("action=create_null")
        }
        assertTrue(createNullLog != null)
        assertTrue(createNullLog!!.message.contains("worker_class_name=com.example.LegacyWorker"))
        assertTrue(createNullLog.message.contains("work_id=$workerId"))
        assertTrue(createNullLog.message.contains("tags=tag3"))

        val fallbackLog = tree.entries.firstOrNull { entry ->
            entry.message.contains("action=fallback")
        }
        assertTrue(fallbackLog != null)
        assertTrue(fallbackLog!!.message.contains("worker_class_name=com.example.LegacyWorker"))
        assertTrue(fallbackLog.message.contains("work_id=$workerId"))
        assertTrue(fallbackLog.message.contains("tags=tag3"))
    }

    @Test
    fun `createWorker logs and throws when fallback returns null`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        every { hiltWorkerFactory.createWorker(any(), any(), any()) } returns null

        val workerId = UUID.randomUUID()
        every { workerParameters.id } returns workerId
        every { workerParameters.tags } returns setOf("tag3")

        val factory = LoggingWorkerFactory(hiltWorkerFactory)

        val thrown = assertThrows(IllegalStateException::class.java) {
            factory.createWorker(context, "com.example.LegacyWorker", workerParameters)
        }
        val exceptionMessage = thrown.message ?: ""
        assertTrue(exceptionMessage.contains("worker_class_name=com.example.LegacyWorker"))
        assertTrue(exceptionMessage.contains("work_id=$workerId"))
        assertTrue(exceptionMessage.contains("tags=tag3"))

        val createNullLog = tree.entries.single()
        assertTrue(createNullLog.message.contains("action=create_null"))
        assertTrue(createNullLog.message.contains("worker_class_name=com.example.LegacyWorker"))
        assertTrue(createNullLog.message.contains("work_id=$workerId"))
        assertTrue(createNullLog.message.contains("tags=tag3"))
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
