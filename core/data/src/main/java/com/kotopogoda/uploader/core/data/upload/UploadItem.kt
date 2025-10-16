package com.kotopogoda.uploader.core.data.upload

import android.net.Uri
import java.time.Instant

data class UploadItem(
    val id: Long,
    val uri: Uri,
    val displayName: String?,
    val size: Long,
    val state: UploadItemState,
    val lastErrorKind: UploadItemErrorKind?,
    val httpCode: Int?,
    val createdAt: Instant,
    val updatedAt: Instant
)
