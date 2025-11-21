package com.kotopogoda.uploader.feature.viewer.di

import android.content.Context
import com.kotopogoda.uploader.feature.viewer.BuildConfig
import com.kotopogoda.uploader.core.data.ml.ModelDefinition
import com.kotopogoda.uploader.core.data.ml.ModelsLock
import com.kotopogoda.uploader.core.data.ml.ModelsLockParser
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceController
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceAdapter
import com.kotopogoda.uploader.feature.viewer.enhance.EnhancerModelsInstaller
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import timber.log.Timber

@Module
@InstallIn(SingletonComponent::class)
object ViewerEnhanceModule {

    @Provides
    @Singleton
    fun provideModelsLock(): ModelsLock {
        return ModelsLockParser.parse(BuildConfig.MODELS_LOCK_JSON)
    }

    @Provides
    @Singleton
    fun provideNativeEnhanceController(): NativeEnhanceController = NativeEnhanceController()

    @Provides
    @Singleton
    @Named("zeroDceChecksums")
    fun provideZeroDceChecksums(lock: ModelsLock): NativeEnhanceController.ModelChecksums {
        return try {
            requireModelChecksums(lock.require("zerodcepp_fp16"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to get zero-dce checksums from models.lock.json")
            throw e
        }
    }

    @Provides
    @Singleton
    @Named("restormerChecksums")
    fun provideRestormerChecksums(lock: ModelsLock): NativeEnhanceController.ModelChecksums {
        return try {
            requireModelChecksums(lock.require("restormer_fp32"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to get restormer checksums from models.lock.json")
            throw e
        }
    }

    @Provides
    @Singleton
    fun provideNativeEnhanceAdapter(
        @ApplicationContext context: Context,
        controller: NativeEnhanceController,
        modelsInstaller: EnhancerModelsInstaller,
        modelsLock: ModelsLock,
        @Named("zeroDceChecksums") zeroDceChecksums: NativeEnhanceController.ModelChecksums,
        @Named("restormerChecksums") restormerChecksums: NativeEnhanceController.ModelChecksums,
    ): NativeEnhanceAdapter {
        return NativeEnhanceAdapter(
            context = context,
            controller = controller,
            modelsInstaller = modelsInstaller,
            modelsLock = modelsLock,
            zeroDceChecksums = zeroDceChecksums,
            restormerChecksums = restormerChecksums,
        )
    }
}

private fun requireModelChecksums(definition: ModelDefinition): NativeEnhanceController.ModelChecksums {
    val filesByExt = definition.filesByExtension()
    val param = filesByExt["param"]?.sha256
        ?: throw IllegalStateException("Для модели ${definition.name} не найден .param в models.lock.json")
    val bin = filesByExt["bin"]?.sha256
        ?: throw IllegalStateException("Для модели ${definition.name} не найден .bin в models.lock.json")
    return NativeEnhanceController.ModelChecksums(param = param, bin = bin)
}
