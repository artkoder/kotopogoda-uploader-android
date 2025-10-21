package com.kotopogoda.uploader.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.kotopogoda.uploader.core.settings.NotificationPermissionProvider
import com.kotopogoda.uploader.core.data.upload.UploadLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

@Singleton
class NotificationPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : NotificationPermissionProvider {

    private val permissionState = MutableStateFlow(isPermissionGranted())
    @VisibleForTesting
    internal var overrideSdkInt: Int? = null

    override fun permissionFlow(): Flow<Boolean> = permissionState.asStateFlow()

    fun canPostNotifications(): Boolean = permissionState.value

    fun refresh() {
        Timber.tag(TAG).i(
            UploadLog.message(
                category = "PERM/REQUEST",
                action = "notifications_refresh",
            )
        )
        permissionState.value = isPermissionGranted()
        Timber.tag(TAG).i(
            UploadLog.message(
                category = "PERM/RESULT",
                action = "notifications_refresh",
                details = arrayOf(
                    "granted" to permissionState.value,
                ),
            )
        )
    }

    private fun isPermissionGranted(): Boolean {
        val sdkInt = overrideSdkInt ?: Build.VERSION.SDK_INT
        return sdkInt < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "Permissions"
    }
}
