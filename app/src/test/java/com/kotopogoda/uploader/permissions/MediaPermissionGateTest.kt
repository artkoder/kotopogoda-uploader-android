package com.kotopogoda.uploader.permissions

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPermissionGateTest {

    @Test
    fun `returns read media images for API 33 and above`() {
        val tiramisuPermissions = mediaReadPermissionFor(Build.VERSION_CODES.TIRAMISU)
        assertEquals(
            Manifest.permission.READ_MEDIA_IMAGES,
            tiramisuPermissions.readPermission
        )
        assertEquals(
            setOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ),
            tiramisuPermissions.allPermissions
        )

        val upsideDownCakePermissions = mediaReadPermissionFor(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        assertEquals(
            Manifest.permission.READ_MEDIA_IMAGES,
            upsideDownCakePermissions.readPermission
        )
        assertEquals(
            setOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ),
            upsideDownCakePermissions.allPermissions
        )
    }

    @Test
    fun `returns read external storage for API 32 and below`() {
        val sPermissions = mediaReadPermissionFor(Build.VERSION_CODES.S_V2)
        assertEquals(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            sPermissions.readPermission
        )
        assertEquals(
            setOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ),
            sPermissions.allPermissions
        )

        val piePermissions = mediaReadPermissionFor(Build.VERSION_CODES.P)
        assertEquals(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            piePermissions.readPermission
        )
        assertEquals(
            setOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            piePermissions.allPermissions
        )
    }
}
