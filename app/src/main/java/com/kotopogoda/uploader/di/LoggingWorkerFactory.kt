package com.kotopogoda.uploader.di

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.kotopogoda.uploader.core.data.upload.UploadLog
import timber.log.Timber
import javax.inject.Inject

open class LoggingWorkerFactory @Inject constructor(
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
                        category = "WORK/Factory",
                        action = "drain_worker_create_error",
                        details = arrayOf(
                            "worker_class_name" to workerClassName,
                            "work_id" to workerParameters.id,
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
        val details = arrayOf(
            "worker_class_name" to workerClassName,
            "work_id" to workerParameters.id,
            "tags" to workerParameters.tags.joinToString(),
        )
        val message = UploadLog.message(
            category = "WORK/Factory",
            action = "create_null",
            details = details,
        )
        Timber.tag(LOG_TAG).w(message)

        val fallbackWorker = fallbackCreateWorker(appContext, workerClassName, workerParameters)
        if (fallbackWorker != null) {
            Timber.tag(LOG_TAG).i(
                UploadLog.message(
                    category = "WORK/Factory",
                    action = "fallback",
                    details = details,
                ),
            )
            return fallbackWorker
        }

        throw IllegalStateException(
            "Не удалось создать воркер: $message",
        )
    }

    protected open fun fallbackCreateWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        DEFAULT_WORKER_FACTORY.createWorkerWithDefaultFallback(
            appContext,
            workerClassName,
            workerParameters,
        )

    private companion object {
        private const val LOG_TAG = "WorkManager"

        private val DEFAULT_WORKER_FACTORY: WorkerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = null
        }
    }
}
