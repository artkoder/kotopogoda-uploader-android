package com.kotopogoda.uploader.work

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.kotopogoda.uploader.core.network.upload.UploadTags
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import timber.log.Timber

class UploadWorkObserverTest {

    private val workManager = mockk<WorkManager>()
    private lateinit var logTree: RecordingTree

    @BeforeTest
    fun setUp() {
        clearAllMocks()
        Timber.uprootAll()
        logTree = RecordingTree()
        Timber.plant(logTree)
    }

    @AfterTest
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun `logs state changes for drain work`() = runTest {
        val flow = MutableSharedFlow<List<WorkInfo>>()
        val querySlot = slot<WorkQuery>()
        every { workManager.getWorkInfosFlow(capture(querySlot)) } returns flow

        val observer = UploadWorkObserver(workManager)
        observer.start(this)
        advanceUntilIdle()

        val workId = java.util.UUID.randomUUID()
        val workInfo = mockk<WorkInfo>()
        every { workInfo.id } returns workId
        every { workInfo.state } returns WorkInfo.State.RUNNING
        every { workInfo.runAttemptCount } returns 0
        every { workInfo.tags } returns setOf(UploadTags.TAG_DRAIN)
        val emptyData = Data.Builder().build()
        every { workInfo.progress } returns emptyData
        every { workInfo.outputData } returns emptyData

        flow.emit(listOf(workInfo))
        advanceUntilIdle()

        assertTrue(
            querySlot.captured.tags.containsAll(
                listOf(UploadTags.TAG_UPLOAD, UploadTags.TAG_POLL, UploadTags.TAG_DRAIN)
            ),
            "Запрос наблюдателя должен включать теги загрузок, опросов и дренера",
        )

        logTree.assertLogged(
            category = "WORK/STATE_CHANGE",
            predicate = { it.contains("action=running") && it.contains("kind=upload") },
        )
        logTree.assertLogged(category = "WORK/ENQUEUE")
        logTree.assertLogged(category = "WORK/START")
    }

    private class RecordingTree : Timber.DebugTree() {
        private data class Entry(val tag: String?, val message: String)

        private val entries = mutableListOf<Entry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += Entry(tag, message)
        }

        fun assertLogged(category: String, predicate: (String) -> Boolean = { true }) {
            assertTrue(
                entries.any { entry ->
                    entry.tag == "WorkManager" &&
                        entry.message.contains("category=$category") &&
                        predicate(entry.message)
                },
                "Ожидалась запись с категорией $category",
            )
        }
    }
}

