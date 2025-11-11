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
import java.io.File
import javax.inject.Named
import javax.inject.Singleton
import timber.log.Timber

@Module
@InstallIn(SingletonComponent::class)
object ViewerEnhanceModule {

    @Provides
    @Singleton
    fun provideModelsLock(@ApplicationContext context: Context): ModelsLock? {
        return try {
            val lockFile = File(context.filesDir, "models/models.lock.json")
            if (!lockFile.exists()) {
                Timber.w("Models lock file not found: ${lockFile.absolutePath}")
                return null
            }
            val json = lockFile.readText()
            ModelsLockParser.parse(json)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse models.lock.json, disabling enhancement")
            null
        }
    }

    @Provides
    @Singleton
    fun provideNativeEnhanceController(): NativeEnhanceController = NativeEnhanceController()

    @Provides
    @Singleton
    @Named("zeroDceChecksum")
    fun provideZeroDceChecksum(lock: ModelsLock?): String? {
        if (lock == null) {
            Timber.w("Models lock is null, zero-dce checksum unavailable")
            return null
        }
        return try {
            requireNcnnChecksum(lock.require("zerodcepp_fp16"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to get zero-dce checksum from models.lock.json")
            null
        }
    }

    @Provides
    @Singleton
    @Named("restormerChecksum")
    fun provideRestormerChecksum(lock: ModelsLock?): String? {
        if (lock == null) {
            Timber.w("Models lock is null, restormer checksum unavailable")
            return null
        }
        return try {
            requireNcnnChecksum(lock.require("restormer_fp16"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to get restormer checksum from models.lock.json")
            null
        }
    }

    // TODO: Заменить сырые String на типизированный value object (Checksums)
    @Provides
    @Singleton
    fun provideNativeEnhanceAdapter(
        @ApplicationContext context: Context,
        controller: NativeEnhanceController,
        @Named("zeroDceChecksum") zeroDceChecksum: String?,
        @Named("restormerChecksum") restormerChecksum: String?,
    ): NativeEnhanceAdapter? {
        if (zeroDceChecksum == null || restormerChecksum == null) {
            Timber.w("Models lock checksums unavailable, enhancement disabled")
            return null
        }
        return NativeEnhanceAdapter(
            context = context,
            controller = controller,
            zeroDceChecksum = zeroDceChecksum,
            restormerChecksum = restormerChecksum,
        )
    }
}

private fun requireNcnnChecksum(definition: ModelDefinition): String =
    requireNcnnFile(definition).sha256

private fun requireNcnnFile(definition: ModelDefinition): ModelFile =
    definition.files.firstOrNull { 
        it.path.endsWith(".param") || it.path.endsWith(".bin") || it.path.endsWith(".ncnn")
    } ?: definition.files.firstOrNull()
        ?: throw IllegalStateException("Для модели ${definition.name} не найдены файлы в models.lock.json")
