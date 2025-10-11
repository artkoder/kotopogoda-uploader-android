package com.kotopogoda.uploader.core.data.photo

enum class PhotoStatus(val value: String) {
    NEW("new"),
    SKIPPED("skipped"),
    TO_PROCESS("to_process"),
    TO_PUBLISH("to_publish");

    companion object {
        fun fromValue(value: String): PhotoStatus = entries.firstOrNull { it.value == value }
            ?: NEW
    }
}
