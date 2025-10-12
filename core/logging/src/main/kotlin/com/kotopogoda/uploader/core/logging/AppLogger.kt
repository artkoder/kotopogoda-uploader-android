package com.kotopogoda.uploader.core.logging

import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

@Singleton
class AppLogger @Inject constructor(
    private val fileLogger: FileLogger,
) {

    private val planted = AtomicBoolean(false)

    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            if (planted.compareAndSet(false, true)) {
                Timber.plant(fileLogger)
            }
        } else {
            if (planted.compareAndSet(true, false)) {
                Timber.uproot(fileLogger)
            }
        }
    }
}
