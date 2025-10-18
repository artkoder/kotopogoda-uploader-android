package com.kotopogoda.uploader.core.network.upload

import androidx.work.NetworkType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class UploadConstraintsHelperTest {

    @Test
    fun `returns wifi constraint before preference is loaded`() {
        val wifiOnlyFlow = MutableSharedFlow<Boolean>()
        val helper = UploadConstraintsHelper(wifiOnlyFlow)

        val constraints = helper.buildConstraints()

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertFalse(helper.shouldUseExpeditedWork())
    }

    @Test
    fun `updates constraints after preference is loaded`() = runBlocking {
        val wifiOnlyFlow = MutableSharedFlow<Boolean>()
        val helper = UploadConstraintsHelper(wifiOnlyFlow)

        wifiOnlyFlow.emit(false)

        val constraints = helper.buildConstraints()

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(helper.shouldUseExpeditedWork())

        wifiOnlyFlow.emit(true)

        val updatedConstraints = helper.buildConstraints()

        assertEquals(NetworkType.UNMETERED, updatedConstraints.requiredNetworkType)
        assertFalse(helper.shouldUseExpeditedWork())
    }
}
