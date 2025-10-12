package com.kotopogoda.uploader.core.logging

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class LogsSharer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logManager: LogManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun prepareShareIntent(): LogsShareResult = withContext(ioDispatcher) {
        val archive = runCatching { logManager.createLogsArchive() }.getOrElse { error ->
            return@withContext LogsShareResult.Error(error)
        }
        if (archive == null) {
            return@withContext LogsShareResult.NoLogs
        }
        val uri = FileProvider.getUriForFile(context, authority(), archive)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        LogsShareResult.Success(intent, archive)
    }

    private fun authority(): String = "${context.packageName}.fileprovider"
}

sealed interface LogsShareResult {
    data class Success(val intent: Intent, val file: File) : LogsShareResult
    data object NoLogs : LogsShareResult
    data class Error(val throwable: Throwable) : LogsShareResult
}
