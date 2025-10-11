package com.kotopogoda.uploader.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay

@Singleton
class KotopogodaApi @Inject constructor() {
    suspend fun uploadCatWeatherReport(report: String): Boolean {
        delay(300)
        return report.isNotBlank()
    }
}
