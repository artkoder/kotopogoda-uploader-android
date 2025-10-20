package com.kotopogoda.uploader.core.logging.diagnostics

data class AppInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val contractVersion: String,
)

interface AppInfoProvider {
    fun getAppInfo(): AppInfo
}
