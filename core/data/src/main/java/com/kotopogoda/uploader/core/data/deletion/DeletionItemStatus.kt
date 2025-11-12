package com.kotopogoda.uploader.core.data.deletion

object DeletionItemStatus {
    const val PENDING = "pending"
    const val CONFIRMED = "confirmed"
    const val FAILED = "failed"
    const val SKIPPED = "skipped"

    fun isTerminal(status: String): Boolean = when (status) {
        CONFIRMED, FAILED, SKIPPED -> true
        else -> false
    }
}
