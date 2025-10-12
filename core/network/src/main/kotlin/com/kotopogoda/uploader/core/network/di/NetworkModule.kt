package com.kotopogoda.uploader.core.network.di

import android.content.Context
import androidx.work.WorkManager
import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.kotopogoda.uploader.core.network.logging.HttpLoggingController
import com.kotopogoda.uploader.core.network.security.HmacInterceptor
import com.kotopogoda.uploader.core.settings.DefaultBaseUrl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.converter.moshi.MoshiConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.NONE
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        loggingInterceptor: HttpLoggingInterceptor,
        hmacInterceptor: HmacInterceptor,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(loggingInterceptor)
            .addInterceptor(hmacInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideMoshiConverterFactory(): MoshiConverterFactory = MoshiConverterFactory.create()

    @Provides
    @Singleton
    fun provideNetworkClientProvider(
        okHttpClient: OkHttpClient,
        moshiConverterFactory: MoshiConverterFactory,
        @DefaultBaseUrl defaultBaseUrl: String,
    ): NetworkClientProvider = NetworkClientProvider(
        okHttpClient = okHttpClient,
        converterFactory = moshiConverterFactory,
        defaultBaseUrl = defaultBaseUrl,
    )

    @Provides
    @Singleton
    fun provideHttpLoggingController(
        loggingInterceptor: HttpLoggingInterceptor,
    ): HttpLoggingController = HttpLoggingController(loggingInterceptor)

    @Provides
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

}
