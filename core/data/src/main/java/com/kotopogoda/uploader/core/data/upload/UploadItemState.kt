package com.kotopogoda.uploader.core.data.upload

enum class UploadItemState(val rawValue: String) {
    PENDING("pending"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    companion object {
        fun fromRawValue(raw: String): UploadItemState {
            return entries.firstOrNull { it.rawValue == raw } ?: PENDING
        }
    }
}
