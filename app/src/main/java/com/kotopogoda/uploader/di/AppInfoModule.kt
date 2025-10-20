package com.kotopogoda.uploader.di

import com.kotopogoda.uploader.BuildConfig
import com.kotopogoda.uploader.core.logging.diagnostics.AppInfo
import com.kotopogoda.uploader.core.logging.diagnostics.AppInfoProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppInfoModule {

    @Binds
    abstract fun bindAppInfoProvider(impl: BuildConfigAppInfoProvider): AppInfoProvider
}

@Singleton
class BuildConfigAppInfoProvider @Inject constructor() : AppInfoProvider {
    override fun getAppInfo(): AppInfo {
        return AppInfo(
            packageName = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            contractVersion = BuildConfig.CONTRACT_VERSION,
        )
    }
}
