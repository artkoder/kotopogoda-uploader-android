package com.kotopogoda.uploader.di

import androidx.hilt.work.HiltWorkerFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerConfigModule {

    @Provides
    @Singleton
    fun provideLoggingWorkerFactory(
        hiltWorkerFactory: HiltWorkerFactory,
    ): LoggingWorkerFactory = LoggingWorkerFactory(hiltWorkerFactory)
}
