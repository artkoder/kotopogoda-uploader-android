package com.kotopogoda.uploader.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import timber.log.Timber

class NotificationPermissionCheckerTest {

    private val tree = RecordingTree()

    @BeforeTest
    fun setUp() {
        mockkStatic(Build.VERSION::class)
        mockkStatic(ContextCompat::class)
        every { Build.VERSION.SDK_INT } returns Build.VERSION_CODES.TIRAMISU
        Timber.uprootAll()
        Timber.plant(tree)
    }

    @AfterTest
    fun tearDown() {
        Timber.uprootAll()
        unmockkAll()
        tree.clear()
    }

    @Test
    fun refresh_logsRequestAndResult() {
        val context = mockk<Context>(relaxed = true)
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) } returns PackageManager.PERMISSION_GRANTED
        val checker = NotificationPermissionChecker(context)

        tree.clear()
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) } returns PackageManager.PERMISSION_DENIED

        checker.refresh()

        assertTrue(tree.logs.any { it.message.contains("PERM/REQUEST") })
        val resultLog = tree.logs.firstOrNull { it.message.contains("PERM/RESULT") }
        assertTrue(resultLog != null && resultLog.message.contains("granted=false"))
    }

    private class RecordingTree : Timber.DebugTree() {
        val logs = mutableListOf<Entry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += Entry(priority = priority, tag = tag.orEmpty(), message = message)
        }

        fun clear() {
            logs.clear()
        }
    }

    private data class Entry(
        val priority: Int,
        val tag: String,
        val message: String,
    )
}
