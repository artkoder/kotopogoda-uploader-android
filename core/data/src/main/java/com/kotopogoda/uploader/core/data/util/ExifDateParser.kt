package com.kotopogoda.uploader.core.data.util

import androidx.exifinterface.media.ExifInterface
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object ExifDateParser {
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun extractCaptureTimestampMillis(exifInterface: ExifInterface): Long? {
        val primaryTags = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL to ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_DATETIME to ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_DATETIME_DIGITIZED to ExifInterface.TAG_OFFSET_TIME_DIGITIZED
        )

        for ((dateTag, offsetTag) in primaryTags) {
            val dateValue = exifInterface.getAttribute(dateTag) ?: continue
            val offsetValue = exifInterface.getAttribute(offsetTag)
            parseDate(dateValue, offsetValue)?.let { return it }
        }

        return null
    }

    private fun parseDate(dateValue: String, offsetValue: String?): Long? {
        return try {
            if (!offsetValue.isNullOrBlank()) {
                val zoneOffset = runCatching { ZoneOffset.of(offsetValue.trim()) }.getOrNull()
                if (zoneOffset != null) {
                    val localDateTime = LocalDateTime.parse(dateValue.trim(), dateFormatter)
                    return localDateTime.atOffset(zoneOffset).toInstant().toEpochMilli()
                }
            }

            val localDateTime = LocalDateTime.parse(dateValue.trim(), dateFormatter)
            localDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (error: DateTimeParseException) {
            null
        }
    }
}
