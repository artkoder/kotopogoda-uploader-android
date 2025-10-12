package com.kotopogoda.uploader.core.network.client

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@Singleton
class NetworkClientProvider @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val converterFactory: MoshiConverterFactory,
    defaultBaseUrl: String,
) {

    private val lock = Any()
    private val retrofitRef = AtomicReference<Retrofit>()
    private val defaultHttpUrl: HttpUrl = normalizeDefault(defaultBaseUrl)
    @Volatile
    private var currentBaseUrl: HttpUrl = defaultHttpUrl

    init {
        retrofitRef.set(createRetrofit(currentBaseUrl))
    }

    fun updateBaseUrl(baseUrl: String) {
        val target = normalizeWithFallback(baseUrl, currentBaseUrl)
        synchronized(lock) {
            if (target == currentBaseUrl) {
                return
            }
            currentBaseUrl = target
            retrofitRef.set(createRetrofit(target))
        }
    }

    fun <T> create(service: Class<T>): T = retrofitRef.get().create(service)

    private fun createRetrofit(baseUrl: HttpUrl): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(converterFactory)
            .client(okHttpClient)
            .build()
    }

    private fun normalizeDefault(raw: String): HttpUrl {
        val candidate = raw.trim().ifEmpty { DEFAULT_PLACEHOLDER }
        val normalized = if (candidate.endsWith('/')) candidate else "$candidate/"
        return normalized.toHttpUrlOrNull() ?: DEFAULT_PLACEHOLDER.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid base URL: $raw")
    }

    private fun normalizeWithFallback(raw: String, fallback: HttpUrl): HttpUrl {
        val candidate = raw.trim().ifEmpty { fallback.toString() }
        val normalized = if (candidate.endsWith('/')) candidate else "$candidate/"
        return normalized.toHttpUrlOrNull() ?: fallback
    }

    private companion object {
        private const val DEFAULT_PLACEHOLDER = "https://localhost/"
    }
}
