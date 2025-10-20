package com.kotopogoda.uploader.logging

import com.kotopogoda.uploader.core.logging.diagnostic.AppInfoProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface AppInfoModule {

    @Binds
    fun bindAppInfoProvider(impl: AppInfoProviderImpl): AppInfoProvider
}
