package com.kotopogoda.uploader.core.data.util

import android.net.Uri
import android.provider.MediaStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class UriAccessTest {

    @Test
    fun `isMediaUri returns true for MediaStore authority`() {
        val uri = Uri.parse("content://${MediaStore.AUTHORITY}/external/images/media/42")
        assertTrue(isMediaUri(uri))
    }

    @Test
    fun `isMediaUri returns true for media documents authority`() {
        val uri = Uri.parse("content://com.android.providers.media.documents/document/image:42")
        assertTrue(isMediaUri(uri))
    }

    @Test
    fun `isMediaUri returns false for downloads documents authority`() {
        val uri = Uri.parse("content://com.android.providers.downloads.documents/document/123")
        assertFalse(isMediaUri(uri))
    }

    @Test
    fun `isMediaUri returns false for file scheme`() {
        val uri = Uri.parse("file:///storage/emulated/0/DCIM/photo.jpg")
        assertFalse(isMediaUri(uri))
    }

    @Test
    fun `isMediaUri returns false for http scheme`() {
        val uri = Uri.parse("https://example.com/image.jpg")
        assertFalse(isMediaUri(uri))
    }

    @Test
    fun `isMediaUri returns false for content with unknown authority`() {
        val uri = Uri.parse("content://com.example.custom/path/42")
        assertFalse(isMediaUri(uri))
    }

    @Test
    fun `isMediaUri returns false for content without authority`() {
        val uri = Uri.parse("content:///path")
        assertFalse(isMediaUri(uri))
    }
}
