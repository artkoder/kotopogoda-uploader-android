package com.kotopogoda.uploader

import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.kotopogoda.uploader.core.data.database.KotopogodaDatabase
import com.kotopogoda.uploader.core.data.upload.UploadItemDao
import com.kotopogoda.uploader.core.data.upload.UploadItemEntity
import com.kotopogoda.uploader.core.data.upload.UploadItemState
import com.kotopogoda.uploader.core.network.upload.QUEUE_DRAIN_WORK_NAME
import com.kotopogoda.uploader.upload.UploadStartupInitializer
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class QueueDrainStartupTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var uploadItemDao: UploadItemDao

    @Inject
    lateinit var uploadStartupInitializer: UploadStartupInitializer

    @Inject
    lateinit var database: KotopogodaDatabase

    private lateinit var workManager: WorkManager
    private lateinit var logTree: RecordingTree

    @Before
    fun setUp() {
        hiltRule.inject()
        val context = ApplicationProvider.getApplicationContext<KotopogodaUploaderApp>()
        val baseConfig = context.workManagerConfiguration
        val testConfig = Configuration.Builder()
            .setWorkerFactory(baseConfig.workerFactory)
            .setMinimumLoggingLevel(Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, testConfig)
        workManager = WorkManager.getInstance(context)
        logTree = RecordingTree()
        Timber.uprootAll()
        Timber.plant(logTree)
        database.clearAllTables()
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    @Test
    fun queueDrainWorkerRunsAfterStartupWhenItemsQueued() = runBlocking {
        val now = System.currentTimeMillis()
        val entity = UploadItemEntity(
            photoId = "photo-$now",
            idempotencyKey = "idempotency-$now",
            uri = Uri.parse("content://kotopogoda/test/$now").toString(),
            displayName = "photo.jpg",
            size = 1024L,
            state = UploadItemState.QUEUED.rawValue,
            createdAt = now,
            updatedAt = now,
        )
        uploadItemDao.insert(entity)

        uploadStartupInitializer.ensureUploadRunningIfQueued()

        val workInfos = workManager.getWorkInfosForUniqueWork(QUEUE_DRAIN_WORK_NAME).get(5, TimeUnit.SECONDS)
        check(workInfos.isNotEmpty()) { "Expected queue drain work to be scheduled" }
        val workInfo = workInfos.first()

        val testDriver = WorkManagerTestInitHelper.getTestDriver(ApplicationProvider.getApplicationContext<KotopogodaUploaderApp>())
            ?: error("TestDriver was not initialized")
        testDriver.setInitialDelayMet(workInfo.id)
        testDriver.setAllConstraintsMet(workInfo.id)

        val resultInfo = workManager.getWorkInfoById(workInfo.id).get(5, TimeUnit.SECONDS)
        check(resultInfo.state == WorkInfo.State.SUCCEEDED) { "Queue drain work did not succeed" }

        logTree.assertActionLogged("drain_worker_start")
    }

    private fun RecordingTree.assertActionLogged(action: String) {
        val match = logs.any { entry ->
            entry.tag == "WorkManager" && entry.message?.contains("action=$action") == true
        }
        check(match) { "Ожидалось наличие лога с action=$action" }
    }

    private class RecordingTree : Timber.DebugTree() {
        val logs = mutableListOf<LogEntry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += LogEntry(priority, tag, message, t)
        }
    }

    private data class LogEntry(
        val priority: Int,
        val tag: String?,
        val message: String?,
        val throwable: Throwable?,
    )
}
