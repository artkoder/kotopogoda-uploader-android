package com.kotopogoda.uploader.core.logging.test

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

/**
 * Базовый класс для Robolectric тестов с предустановленными настройками:
 * - LooperMode.PAUSED для детерминистических тестов
 * - SDK 34 по умолчанию
 * - Отключенное логирование в stdout для уменьшения шума
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    application = android.app.Application::class
)
@LooperMode(LooperMode.Mode.PAUSED)
abstract class RobolectricTestBase {

    @Before
    fun setUpRobolectricBase() {
        // Отключаем вывод логов в stdout для уменьшения шума
        ShadowLog.stream = System.out
    }

    /**
     * Возвращает тестовый ApplicationContext.
     */
    protected fun getContext(): android.content.Context {
        return ApplicationProvider.getApplicationContext()
    }
}
