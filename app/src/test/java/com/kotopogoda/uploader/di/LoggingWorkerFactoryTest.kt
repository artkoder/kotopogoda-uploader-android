package com.kotopogoda.uploader.di

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
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
        assertSame(error, tree.throwable)
        val message = tree.message
        assertTrue(message.contains("action=drain_worker_create_error"))
        assertTrue(message.contains("workerClassName=com.example.QueueDrainWorker"))
        assertTrue(message.contains("id=$workerId"))
        assertTrue(message.contains("tags=tag1, tag2"))
    }

    private class RecordingTree : Timber.Tree() {
        var message: String = ""
        var throwable: Throwable? = null

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            this.message = message
            this.throwable = t
        }
    }
}
