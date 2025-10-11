package com.kotopogoda.uploader.core.data.photo

data class PhotoCountByMonth(
    val monthStartEpochMillis: Long,
    val count: Long
)

data class PhotoCountByDay(
    val dayStartEpochMillis: Long,
    val count: Long
)
