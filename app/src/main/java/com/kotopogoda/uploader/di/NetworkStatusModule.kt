package com.kotopogoda.uploader.di

import com.kotopogoda.uploader.core.logging.diagnostics.NetworkStatusProvider
import com.kotopogoda.uploader.core.network.connectivity.NetworkMonitor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkStatusModule {

    @Binds
    abstract fun bindNetworkStatusProvider(impl: NetworkMonitorStatusProvider): NetworkStatusProvider
}

@Singleton
class NetworkMonitorStatusProvider @Inject constructor(
    private val networkMonitor: NetworkMonitor,
) : NetworkStatusProvider {
    override fun isNetworkValidated(): Boolean = networkMonitor.isNetworkValidated.value
}
