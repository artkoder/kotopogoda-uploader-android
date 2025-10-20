package com.kotopogoda.uploader.core.logging.diagnostic

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

interface DeviceInfoProvider {
    fun deviceInfo(): DeviceInfo
}

@Singleton
class BuildDeviceInfoProvider @Inject constructor() : DeviceInfoProvider {

    override fun deviceInfo(): DeviceInfo {
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }
}
