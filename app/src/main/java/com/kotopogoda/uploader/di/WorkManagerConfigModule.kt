package com.kotopogoda.uploader.di

import android.util.Log
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerConfigModule {

    @Provides
    @Singleton
    fun provideWorkManagerConfiguration(
        workerFactory: LoggingWorkerFactory,
    ): Configuration {
        return Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(Log.INFO)
            .setExecutor(Executors.newFixedThreadPool(2))
            .build()
    }

    @Provides
    @Singleton
    fun provideLoggingWorkerFactory(
        hiltWorkerFactory: HiltWorkerFactory,
    ): LoggingWorkerFactory = LoggingWorkerFactory(hiltWorkerFactory)
}
