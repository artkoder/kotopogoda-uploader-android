package com.kotopogoda.uploader.feature.viewer.di

import com.kotopogoda.uploader.feature.viewer.BuildConfig
import com.kotopogoda.uploader.core.data.ml.ModelBackend
import com.kotopogoda.uploader.core.data.ml.ModelDefinition
import com.kotopogoda.uploader.core.data.ml.ModelsLock
import com.kotopogoda.uploader.core.data.ml.ModelsLockParser
import com.kotopogoda.uploader.feature.viewer.enhance.EnhanceEngine
import com.kotopogoda.uploader.feature.viewer.enhance.backend.RestormerBackendTflite
import com.kotopogoda.uploader.feature.viewer.enhance.backend.ZeroDceBackendTflite
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object ViewerEnhanceModule {

    @Provides
    @Singleton
    fun provideModelsLock(): ModelsLock = ModelsLockParser.parse(BuildConfig.MODELS_LOCK_JSON)

    @Provides
    @Singleton
    @Named("zero_dce_model_path")
    fun provideZeroDceModelPath(lock: ModelsLock): String = requireTflitePath(lock.require("zerodcepp_fp16"))

    @Provides
    @Singleton
    @Named("restormer_model_path")
    fun provideRestormerModelPath(lock: ModelsLock): String = requireTflitePath(lock.require("restormer_fp16"))

    @Provides
    @Singleton
    fun provideZeroDceModel(
        lock: ModelsLock,
        tflite: ZeroDceBackendTflite,
    ): EnhanceEngine.ZeroDceModel {
        val definition = lock.require("zerodcepp_fp16")
        return when (definition.backend) {
            ModelBackend.TFLITE -> tflite
            ModelBackend.NCNN -> throw IllegalStateException("Zero-DCE++ NCNN backend пока не поддержан")
        }
    }

    @Provides
    @Singleton
    fun provideRestormerModel(
        lock: ModelsLock,
        tflite: RestormerBackendTflite,
    ): EnhanceEngine.RestormerModel {
        val definition = lock.require("restormer_fp16")
        return when (definition.backend) {
            ModelBackend.TFLITE -> tflite
            ModelBackend.NCNN -> throw IllegalStateException("Restormer NCNN backend пока не поддержан")
        }
    }

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

private fun requireTflitePath(definition: ModelDefinition): String {
    val file = definition.files.firstOrNull { it.path.endsWith(".tflite") }
        ?: throw IllegalStateException("Для модели ${definition.name} не найден TFLite-файл в models.lock.json")
    return file.path
}
