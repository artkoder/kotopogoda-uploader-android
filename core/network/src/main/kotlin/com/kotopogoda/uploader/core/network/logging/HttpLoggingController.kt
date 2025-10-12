package com.kotopogoda.uploader.core.network.logging

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.logging.HttpLoggingInterceptor

@Singleton
class HttpLoggingController @Inject constructor(
    private val interceptor: HttpLoggingInterceptor,
) {

    private val enabled = AtomicBoolean(false)

    fun setEnabled(value: Boolean) {
        val previous = enabled.getAndSet(value)
        if (previous != value) {
            interceptor.level = if (value) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
    }
}
