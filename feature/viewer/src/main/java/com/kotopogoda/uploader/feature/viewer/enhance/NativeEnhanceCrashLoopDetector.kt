package com.kotopogoda.uploader.feature.viewer.enhance

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber

class NativeEnhanceCrashLoopDetector(
    private val preferences: SharedPreferences,
) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val crashLoopDetectedOnStart: Boolean

    init {
        val wasRunning = preferences.getBoolean(KEY_ENHANCE_RUNNING, false)
        crashLoopDetectedOnStart = wasRunning
        if (wasRunning) {
            Timber.tag(TAG).e("enhance_crash_loop_detected")
            EnhanceLogging.logEvent("enhance_crash_loop_detected")
            clearEnhanceRunningFlag()
        }
    }

    fun isCrashLoopSuspected(): Boolean = crashLoopDetectedOnStart

    fun markEnhanceRunning() {
        preferences.edit().putBoolean(KEY_ENHANCE_RUNNING, true).apply()
    }

    fun clearEnhanceRunningFlag() {
        preferences.edit().putBoolean(KEY_ENHANCE_RUNNING, false).apply()
    }

    companion object {
        private const val TAG = "NativeEnhanceCrashLoop"
        private const val PREFS_NAME = "enhance_prefs"
        private const val KEY_ENHANCE_RUNNING = "enhance_running"
    }
}
