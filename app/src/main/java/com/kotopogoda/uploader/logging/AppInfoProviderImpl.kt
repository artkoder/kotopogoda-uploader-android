package com.kotopogoda.uploader.logging

import com.kotopogoda.uploader.BuildConfig
import com.kotopogoda.uploader.core.logging.diagnostic.AppInfo
import com.kotopogoda.uploader.core.logging.diagnostic.AppInfoProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppInfoProviderImpl @Inject constructor() : AppInfoProvider {
    override fun appInfo(): AppInfo {
        return AppInfo(
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            contractVersion = BuildConfig.CONTRACT_VERSION,
            buildType = BuildConfig.BUILD_TYPE,
        )
    }
}
