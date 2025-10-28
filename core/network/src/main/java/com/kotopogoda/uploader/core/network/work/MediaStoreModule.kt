package com.kotopogoda.uploader.core.network.work

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaStoreModule {

    @Binds
    abstract fun bindMediaStoreDeleteLauncher(
        impl: RealMediaStoreDeleteLauncher,
    ): MediaStoreDeleteLauncher
}
