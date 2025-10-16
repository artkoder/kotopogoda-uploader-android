package com.kotopogoda.uploader.core.data.upload

enum class UploadItemState(val rawValue: String) {
    QUEUED("queued"),
    PROCESSING("processing"),
    SUCCEEDED("succeeded"),
    FAILED("failed");

    companion object {
        fun fromRawValue(raw: String): UploadItemState? = values().firstOrNull { it.rawValue == raw }
    }
}
