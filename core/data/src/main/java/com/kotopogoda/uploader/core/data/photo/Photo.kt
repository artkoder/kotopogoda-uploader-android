package com.kotopogoda.uploader.core.data.photo

data class Photo(
    val id: String,
    val uri: String,
    val relPath: String?,
    val sha256: String,
    val takenAt: Long?,
    val size: Long,
    val mime: String,
    val status: PhotoStatus,
    val lastActionAt: Long?
)

fun PhotoEntity.toPhoto(): Photo = Photo(
    id = id,
    uri = uri,
    relPath = relPath,
    sha256 = sha256,
    takenAt = takenAt,
    size = size,
    mime = mime,
    status = PhotoStatus.fromValue(status),
    lastActionAt = lastActionAt
)
