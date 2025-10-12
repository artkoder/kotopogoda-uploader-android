package com.kotopogoda.uploader.core.network.di

import com.kotopogoda.uploader.api.infrastructure.ApiClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkModuleTest {

    @Test
    fun `provideApiClient uses prod base url`() {
        val okHttpClient = OkHttpClient.Builder().build()
        val prodBaseUrl = "https://cat-weather-new.fly.dev/v1"

        val apiClient = NetworkModule.provideApiClient(okHttpClient, prodBaseUrl)

        val baseUrlField = ApiClient::class.java.getDeclaredField("baseUrl").apply {
            isAccessible = true
        }
        val actualBaseUrl = baseUrlField.get(apiClient) as String

        assertEquals(prodBaseUrl.trimEnd('/') + "/", actualBaseUrl)
    }
}
