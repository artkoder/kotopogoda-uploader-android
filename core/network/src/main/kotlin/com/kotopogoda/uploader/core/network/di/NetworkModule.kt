package com.kotopogoda.uploader.core.network.di

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.WorkManager
import com.kotopogoda.uploader.api.infrastructure.ApiClient
import com.kotopogoda.uploader.core.data.upload.UploadLog
import com.kotopogoda.uploader.core.logging.HttpFileLogger
import com.kotopogoda.uploader.core.network.api.UploadApi
import com.kotopogoda.uploader.core.network.client.NetworkClientProvider
import com.kotopogoda.uploader.core.network.logging.HttpLoggingController
import com.kotopogoda.uploader.core.network.security.HmacInterceptor
import com.kotopogoda.uploader.core.settings.DefaultBaseUrl
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.util.UUID
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.converter.moshi.MoshiConverterFactory
import timber.log.Timber

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideLoggingInterceptor(
        httpFileLogger: HttpFileLogger,
    ): HttpLoggingInterceptor = HttpLoggingInterceptor(httpFileLogger).apply {
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
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideMoshiConverterFactory(moshi: Moshi): MoshiConverterFactory = MoshiConverterFactory.create(moshi)

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
    fun provideUploadApi(
        networkClientProvider: NetworkClientProvider,
    ): UploadApi = networkClientProvider.create(UploadApi::class.java)

    @Provides
    @Singleton
    fun provideApiClient(
        okHttpClient: OkHttpClient,
        @DefaultBaseUrl defaultBaseUrl: String,
    ): ApiClient {
        val normalizedBasePath = defaultBaseUrl.trimEnd('/')
        return ApiClient(
            baseUrl = normalizedBasePath,
            okHttpClientBuilder = okHttpClient.newBuilder(),
        )
    }

    @Provides
    @Singleton
    fun provideHttpLoggingController(
        loggingInterceptor: HttpLoggingInterceptor,
    ): HttpLoggingController = HttpLoggingController(loggingInterceptor)

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    @Provides
    fun provideNonceProvider(): () -> String = { UUID.randomUUID().toString() }

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager {
        val initialized = WorkManager.isInitialized()
        Timber.tag("WorkManager").i(
            UploadLog.message(
                category = "WORK/Factory",
                action = "lazy_get",
                details = arrayOf(
                    "initialized" to initialized,
                ),
            ),
        )
        check(initialized) {
            UploadLog.message(
                category = "WORK/Factory",
                action = "lazy_get_before_init",
            )
        }
        return WorkManager.getInstance(context)
    }

    @Provides
    fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

}
