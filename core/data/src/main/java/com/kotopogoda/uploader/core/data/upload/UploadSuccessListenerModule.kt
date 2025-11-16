package com.kotopogoda.uploader.core.data.upload

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class UploadSuccessListenerModule {

    @Multibinds
    abstract fun bindUploadSuccessListeners(): Set<UploadSuccessListener>
}
