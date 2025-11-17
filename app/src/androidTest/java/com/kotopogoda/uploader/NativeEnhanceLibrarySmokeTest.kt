package com.kotopogoda.uploader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.kotopogoda.uploader.feature.viewer.enhance.NativeEnhanceController
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Смоук-тест, который проверяет, что системный загрузчик библиотек может
 * подтянуть kotopogoda_enhance без UnsatisfiedLinkError. Это гарантирует,
 * что OpenMP и остальные нативные зависимости корректно упакованы.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class NativeEnhanceLibrarySmokeTest {

    @Test
    fun loadLibrary_succeeds() {
        NativeEnhanceController.loadLibrary()
    }
}
