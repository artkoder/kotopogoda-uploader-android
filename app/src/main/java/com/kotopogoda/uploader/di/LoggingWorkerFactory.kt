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

    private val defaultWorkerFactory: WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = null
    }

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val details = arrayOf(
            "worker_class_name" to workerClassName,
            "work_id" to workerParameters.id,
            "tags" to workerParameters.tags.joinToString(),
        )
        Timber.tag(LOG_TAG).i(
            UploadLog.message(
                category = "WORK/Factory",
                action = "attempt_create",
                details = details,
            ),
        )
        for (factory in delegates) {
            val worker = try {
                factory.createWorker(appContext, workerClassName, workerParameters)
            } catch (error: Throwable) {
                Timber.tag(LOG_TAG).e(
                    error,
                    UploadLog.message(
                        category = "WORK/Factory",
                        action = "drain_worker_create_error",
                        details = details,
                    ),
                )
                throw error
            }
            if (worker != null) {
                Timber.tag(LOG_TAG).i(
                    UploadLog.message(
                        category = "WORK/Factory",
                        action = "delegate_success",
                        details = details + arrayOf(
                            "factory" to factory.javaClass.name,
                        ),
                    ),
                )
                return worker
            }
            if (factory === hiltWorkerFactory) {
                Timber.tag(LOG_TAG).w(
                    UploadLog.message(
                        category = "WORK/Factory",
                        action = "hilt_null",
                        details = details,
                    ),
                )
            }
        }
        val message = UploadLog.message(
            category = "WORK/Factory",
            action = "create_null",
            details = details,
        )
        Timber.tag(LOG_TAG).w(message)

        Timber.tag(LOG_TAG).i(
            UploadLog.message(
                category = "WORK/Factory",
                action = "fallback_start",
                details = details,
            ),
        )

        val fallbackWorker = try {
            fallbackCreateWorker(appContext, workerClassName, workerParameters)
        } catch (error: Throwable) {
            Timber.tag(LOG_TAG).e(
                error,
                UploadLog.message(
                    category = "WORK/Factory",
                    action = "fallback_error",
                    details = details,
                ),
            )
            null
        }

        val fallbackMessage = UploadLog.message(
            category = "WORK/Factory",
            action = "fallback",
            details = details + arrayOf(
                "result" to (fallbackWorker?.javaClass?.name ?: "null"),
            ),
        )
        if (fallbackWorker != null) {
            Timber.tag(LOG_TAG).i(fallbackMessage)
        } else {
            Timber.tag(LOG_TAG).w(fallbackMessage)
            Timber.tag(LOG_TAG).e(
                UploadLog.message(
                    category = "WORK/Factory",
                    action = "fallback_null",
                    details = details,
                ),
            )
        }

        return fallbackWorker
    }

    protected open fun fallbackCreateWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        defaultWorkerFactory.createWorkerWithDefaultFallback(
            appContext,
            workerClassName,
            workerParameters,
        )

    private companion object {
        private const val LOG_TAG = "WorkManager"
    }
}
