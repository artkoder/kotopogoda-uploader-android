package com.kotopogoda.uploader.di

import com.kotopogoda.uploader.BuildConfig
import com.kotopogoda.uploader.core.settings.DefaultBaseUrl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppSettingsModule {

    @Provides
    @DefaultBaseUrl
    fun provideDefaultBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides
    @Named(DOCS_URL)
    fun provideDocsUrl(): String = "https://kotopogoda.ru/redoc"

    const val DOCS_URL: String = "settings_docs_url"
}
