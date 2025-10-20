package com.kotopogoda.uploader

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kotopogoda.uploader.core.logging.AppLogger
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppStartupLoggingTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var appLogger: AppLogger

    @Before
    fun setUp() {
        hiltRule.inject()
        ApplicationProvider.getApplicationContext<KotopogodaUploaderApp>()
    }

    @Test
    fun appLoggerEnabledImmediately() {
        val field = AppLogger::class.java.getDeclaredField("planted")
        field.isAccessible = true
        val planted = field.get(appLogger) as AtomicBoolean
        assertTrue("Ожидалось включение логгера при старте приложения", planted.get())
    }
}
