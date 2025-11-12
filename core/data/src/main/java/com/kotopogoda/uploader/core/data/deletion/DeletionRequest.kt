package com.kotopogoda.uploader.core.data.deletion

data class DeletionRequest(
    val mediaId: Long,
    val contentUri: String,
    val displayName: String?,
    val sizeBytes: Long?,
    val dateTaken: Long?,
    val reason: String,
)
