package com.kotopogoda.uploader.core.network.upload

import androidx.work.NetworkType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class UploadConstraintsHelperTest {

    @Test
    fun `returns null state before preference is loaded`() {
        val wifiOnlyFlow = MutableSharedFlow<Boolean>()
        val helper = UploadConstraintsHelper(wifiOnlyFlow)

        assertNull(helper.constraintsState.value)
        assertNull(helper.wifiOnlyUploadsState.value)
    }

    @Test
    fun `updates constraints after preference is loaded`() = runBlocking {
        val wifiOnlyFlow = MutableSharedFlow<Boolean>()
        val helper = UploadConstraintsHelper(wifiOnlyFlow)

        wifiOnlyFlow.emit(false)

        val constraints = helper.constraintsState.value

        requireNotNull(constraints)

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(helper.shouldUseExpeditedWork())

        wifiOnlyFlow.emit(true)

        val updatedConstraints = helper.constraintsState.value

        requireNotNull(updatedConstraints)

        assertEquals(NetworkType.UNMETERED, updatedConstraints.requiredNetworkType)
        assertFalse(helper.shouldUseExpeditedWork())
    }

    @Test
    fun `awaitConstraints waits for preference and caches result`() = runBlocking {
        val wifiOnlyFlow = MutableSharedFlow<Boolean>()
        val helper = UploadConstraintsHelper(wifiOnlyFlow)

        val awaiting = async { helper.awaitConstraints() }

        assertTrue(awaiting.isActive)

        wifiOnlyFlow.emit(false)

        val constraints = awaiting.await()

        requireNotNull(constraints)
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)

        val cached = helper.awaitConstraints()

        requireNotNull(cached)
        assertTrue(constraints === cached)
    }
}
