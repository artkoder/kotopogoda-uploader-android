package com.kotopogoda.uploader.core.network.di

import com.kotopogoda.uploader.core.network.work.AndroidWorkManagerProvider
import com.kotopogoda.uploader.core.work.WorkManagerProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkManagerProviderModule {

    @Binds
    abstract fun bindWorkManagerProvider(
        provider: AndroidWorkManagerProvider,
    ): WorkManagerProvider
}
