package com.kotopogoda.uploader.core.data.upload

enum class UploadItemState(val rawValue: String) {
    QUEUED("queued"),
    UPLOADING("uploading"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromRawValue(rawValue: String?): UploadItemState {
            if (rawValue.isNullOrBlank()) return QUEUED
            return entries.firstOrNull { it.rawValue == rawValue } ?: QUEUED
        }
    }
}
