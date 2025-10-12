package com.kotopogoda.uploader.core.network.upload

import androidx.work.ForegroundInfo
import java.util.UUID

interface UploadForegroundDelegate {
    fun create(displayName: String, progress: Int, workId: UUID): ForegroundInfo
}
