package com.kotopogoda.uploader.di

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.data.upload.UploadLog
import timber.log.Timber
import javax.inject.Inject

class LoggingWorkerFactory @Inject constructor(
    private val hiltWorkerFactory: HiltWorkerFactory,
) : WorkerFactory() {

    private val delegates: List<WorkerFactory> = listOf(hiltWorkerFactory)

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        for (factory in delegates) {
            val worker = try {
                factory.createWorker(appContext, workerClassName, workerParameters)
            } catch (error: Throwable) {
                Timber.tag(LOG_TAG).e(
                    error,
                    UploadLog.message(
                        action = "drain_worker_create_error",
                        details = arrayOf(
                            "workerClassName" to workerClassName,
                            "id" to workerParameters.id,
                            "tags" to workerParameters.tags.joinToString(),
                        ),
                    ),
                )
                throw error
            }
            if (worker != null) {
                return worker
            }
        }
        return null
    }

    private companion object {
        private const val LOG_TAG = "WorkManager"
    }
}
