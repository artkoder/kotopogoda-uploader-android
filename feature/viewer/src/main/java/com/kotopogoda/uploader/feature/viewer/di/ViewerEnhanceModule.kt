package com.kotopogoda.uploader.feature.viewer.di

import android.content.Context
import com.kotopogoda.uploader.feature.viewer.BuildConfig
import com.kotopogoda.uploader.core.data.ml.ModelDefinition
import com.kotopogoda.uploader.core.data.ml.ModelFile
import com.kotopogoda.uploader.core.data.ml.ModelsLock
import com.kotopogoda.uploader.core.data.ml.ModelsLockParser
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceController
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ViewerEnhanceModule {

    @Provides
    @Singleton
    fun provideModelsLock(): ModelsLock = ModelsLockParser.parse(BuildConfig.MODELS_LOCK_JSON)

    @Provides
    @Singleton
    fun provideNativeEnhanceController(): NativeEnhanceController = NativeEnhanceController()

    @Provides
    @Singleton
    @ZeroDceChecksum
    fun provideZeroDceChecksum(lock: ModelsLock): String =
        requireNcnnChecksum(lock.require("zerodcepp_fp16"))

    @Provides
    @Singleton
    @RestormerChecksum
    fun provideRestormerChecksum(lock: ModelsLock): String =
        requireNcnnChecksum(lock.require("restormer_fp16"))

    @Provides
    @Singleton
    fun provideNativeEnhanceAdapter(
        @ApplicationContext context: Context,
        controller: NativeEnhanceController,
        @ZeroDceChecksum zeroDceChecksum: String,
        @RestormerChecksum restormerChecksum: String,
    ): NativeEnhanceAdapter = NativeEnhanceAdapter(
        context = context,
        controller = controller,
        zeroDceChecksum = zeroDceChecksum,
        restormerChecksum = restormerChecksum,
    )
}

private fun requireNcnnChecksum(definition: ModelDefinition): String =
    requireNcnnFile(definition).sha256

private fun requireNcnnFile(definition: ModelDefinition): ModelFile =
    definition.files.firstOrNull { 
        it.path.endsWith(".param") || it.path.endsWith(".bin") || it.path.endsWith(".ncnn")
    } ?: definition.files.firstOrNull()
        ?: throw IllegalStateException("Для модели ${definition.name} не найдены файлы в models.lock.json")
