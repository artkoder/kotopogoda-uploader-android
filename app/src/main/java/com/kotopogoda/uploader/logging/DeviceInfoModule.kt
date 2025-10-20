package com.kotopogoda.uploader.logging

import com.kotopogoda.uploader.core.logging.diagnostic.BuildDeviceInfoProvider
import com.kotopogoda.uploader.core.logging.diagnostic.DeviceInfoProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
interface DeviceInfoModule {

    @Binds
    fun bindDeviceInfoProvider(impl: BuildDeviceInfoProvider): DeviceInfoProvider
}
