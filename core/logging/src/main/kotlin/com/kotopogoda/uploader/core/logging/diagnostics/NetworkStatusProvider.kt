package com.kotopogoda.uploader.core.logging.diagnostics

interface NetworkStatusProvider {
    fun isNetworkValidated(): Boolean
}
