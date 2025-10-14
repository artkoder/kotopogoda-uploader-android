package com.kotopogoda.uploader

import org.junit.Assert.assertEquals
import org.junit.Test

class ContractVersionTest {
    @Test
    fun contractVersionMatchesExpectedTag() {
        assertEquals("v1.4.1", BuildConfig.CONTRACT_VERSION)
    }
}
