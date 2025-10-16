package com.kotopogoda.uploader.core.work

import com.kotopogoda.uploader.core.network.upload.UploadConstraintsProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UploadConstraintsModule {
    @Binds
    @Singleton
    abstract fun bindUploadConstraintsProvider(
        helper: UploadConstraintsHelper,
    ): UploadConstraintsProvider
}
