package com.kotopogoda.uploader.core.network.upload

import com.kotopogoda.uploader.core.data.upload.UploadSuccessListener
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class UploadSuccessListenerBindings {

    @Binds
    @IntoSet
    abstract fun bindUploadCleanupCoordinator(
        coordinator: UploadCleanupCoordinator,
    ): UploadSuccessListener
}
