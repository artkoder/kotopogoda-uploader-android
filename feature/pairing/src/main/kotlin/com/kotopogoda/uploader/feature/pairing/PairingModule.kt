package com.kotopogoda.uploader.feature.pairing

import com.kotopogoda.uploader.feature.pairing.data.PairingRepository
import com.kotopogoda.uploader.feature.pairing.data.PairingRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PairingModule {

    @Binds
    @Singleton
    abstract fun bindPairingRepository(impl: PairingRepositoryImpl): PairingRepository
}
