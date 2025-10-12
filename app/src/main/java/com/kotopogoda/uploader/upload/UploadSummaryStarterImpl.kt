package com.kotopogoda.uploader.upload

import android.content.Context
import com.kotopogoda.uploader.core.network.upload.UploadSummaryStarter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadSummaryStarterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : UploadSummaryStarter {
    override fun ensureRunning() {
        UploadSummaryService.ensureRunningIfNeeded(context)
    }
}
