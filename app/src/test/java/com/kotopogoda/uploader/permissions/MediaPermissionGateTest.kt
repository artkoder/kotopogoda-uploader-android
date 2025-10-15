package com.kotopogoda.uploader.permissions

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPermissionGateTest {

    @Test
    fun `returns read media images for API 33 and above`() {
        assertEquals(
            Manifest.permission.READ_MEDIA_IMAGES,
            mediaReadPermissionFor(Build.VERSION_CODES.TIRAMISU)
        )
        assertEquals(
            Manifest.permission.READ_MEDIA_IMAGES,
            mediaReadPermissionFor(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
    }

    @Test
    fun `returns read external storage for API 32 and below`() {
        assertEquals(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            mediaReadPermissionFor(Build.VERSION_CODES.S_V2)
        )
        assertEquals(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            mediaReadPermissionFor(Build.VERSION_CODES.P)
        )
    }
}
