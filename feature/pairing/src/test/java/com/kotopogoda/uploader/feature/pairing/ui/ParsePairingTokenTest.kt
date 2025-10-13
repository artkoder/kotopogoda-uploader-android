package com.kotopogoda.uploader.feature.pairing.ui

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ParsePairingTokenTest {

    @Test
    fun `returns raw value when it is already a token`() {
        val token = parsePairingToken("abcDEF123_-")

        assertEquals("abcDEF123_-", token)
    }

    @Test
    fun `extracts token from telegram link`() {
        val token = parsePairingToken("tg://resolve?domain=KotopogodaUploaderBot&token=pairing-token")

        assertEquals("pairing-token", token)
    }

    @Test
    fun `extracts token from url path`() {
        val token = parsePairingToken("https://example.com/pairing/pairing-token")

        assertEquals("pairing-token", token)
    }

    @Test
    fun `returns null for invalid input`() {
        val token = parsePairingToken("https://example.com/?foo=bar")

        assertNull(token)
    }
}
