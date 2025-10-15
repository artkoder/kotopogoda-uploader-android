package com.kotopogoda.uploader.core.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.logging.HttpLoggingInterceptor

@Singleton
class HttpFileLogger @Inject constructor(
    @ApplicationContext context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HttpLoggingInterceptor.Logger {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val writer = RotatingLogWriter(
        directoryProvider = { ensureLogsDirectory(context) },
        fileBaseName = HTTP_LOG_BASE_NAME,
        maxFiles = LOG_MAX_FILES,
        maxFileSizeBytes = LOG_MAX_FILE_SIZE_BYTES,
    )

    fun logsDirectory(): File = writer.logsDirectory()

    override fun log(message: String) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val formatted = "[$timestamp] $message"
        scope.launch {
            writer.appendLine(formatted)
        }
    }
}
