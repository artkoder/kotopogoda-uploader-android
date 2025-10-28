package com.kotopogoda.uploader.feature.viewer.di

import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine
import com.kotopogoda.uploader.feature.viewer.enhance.backend.RestormerBackendTflite
import com.kotopogoda.uploader.feature.viewer.enhance.backend.ZeroDceBackendTflite
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ViewerEnhanceModule {

    @Provides
    @Singleton
    fun provideZeroDceModel(impl: ZeroDceBackendTflite): EnhanceEngine.ZeroDceModel = impl

    @Provides
    @Singleton
    fun provideRestormerModel(impl: RestormerBackendTflite): EnhanceEngine.RestormerModel = impl

    @Provides
    @Singleton
    fun provideEnhanceEngine(
        zeroDceModel: EnhanceEngine.ZeroDceModel,
        restormerModel: EnhanceEngine.RestormerModel,
    ): EnhanceEngine = EnhanceEngine(
        zeroDce = zeroDceModel,
        restormer = restormerModel,
    )
}
