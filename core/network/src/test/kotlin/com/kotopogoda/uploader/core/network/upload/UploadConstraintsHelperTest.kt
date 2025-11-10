package com.kotopogoda.uploader.core.network.upload

import androidx.work.NetworkType
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UploadConstraintsHelperTest {

    @Test
    fun `returns default states`() {
        val helper = UploadConstraintsHelper()

        assertEquals(NetworkType.CONNECTED, helper.constraintsState.value?.requiredNetworkType)
    }

    @Test
    fun `awaitConstraints returns connected constraints`() = runTest {
        val helper = UploadConstraintsHelper()

        val constraints = helper.awaitConstraints()

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
    }

    @Test
    fun `shouldUseExpeditedWork returns true`() {
        val helper = UploadConstraintsHelper()

        assertEquals(true, helper.shouldUseExpeditedWork())
    }
}
