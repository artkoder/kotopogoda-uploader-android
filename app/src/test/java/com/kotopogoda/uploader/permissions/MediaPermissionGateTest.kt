package com.kotopogoda.uploader.permissions

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPermissionGateTest {

    @Test
    fun `returns read media images with access location for API 33 and above`() {
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ),
            mediaPermissionsFor(Build.VERSION_CODES.TIRAMISU)
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ),
            mediaPermissionsFor(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        )
    }

    @Test
    fun `returns read external storage with access location for API 29-32`() {
        assertEquals(
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ),
            mediaPermissionsFor(Build.VERSION_CODES.S_V2)
        )
        assertEquals(
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ),
            mediaPermissionsFor(Build.VERSION_CODES.Q)
        )
    }

    @Test
    fun `returns read external storage for API 28 and below`() {
        assertEquals(
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            ),
            mediaPermissionsFor(Build.VERSION_CODES.P)
        )
    }
}
