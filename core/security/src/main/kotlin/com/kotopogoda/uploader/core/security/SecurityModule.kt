package com.kotopogoda.uploader.core.security

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindDeviceCredsStore(impl: DeviceCredsStoreImpl): DeviceCredsStore

    companion object {
        @Provides
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
