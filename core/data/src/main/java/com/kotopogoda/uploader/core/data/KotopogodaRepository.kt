package com.kotopogoda.uploader.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class KotopogodaRepository @Inject constructor() {
    private val uploads = MutableStateFlow<List<String>>(emptyList())

    fun getUploads(): Flow<List<String>> = uploads.asStateFlow()

    fun recordUpload(name: String) {
        uploads.value = uploads.value + name
    }
}
