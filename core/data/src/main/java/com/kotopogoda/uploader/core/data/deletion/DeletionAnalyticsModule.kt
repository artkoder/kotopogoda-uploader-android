package com.kotopogoda.uploader.core.data.deletion

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DeletionAnalyticsModule {

    @Binds
    @Singleton
    abstract fun bindDeletionAnalytics(impl: NoOpDeletionAnalytics): DeletionAnalytics
}
