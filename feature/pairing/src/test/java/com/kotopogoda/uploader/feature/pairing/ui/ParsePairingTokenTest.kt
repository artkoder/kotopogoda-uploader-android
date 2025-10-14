package com.kotopogoda.uploader.feature.pairing.ui

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ParsePairingTokenTest {

    @Test
    fun `returns normalized value when it is already a token`() {
        val token = parsePairingToken("abc234")

        assertEquals("ABC234", token)
    }

    @Test
    fun `extracts token from telegram link`() {
        val token = parsePairingToken("tg://resolve?domain=KotopogodaUploaderBot&token=pair23")

        assertEquals("PAIR23", token)
    }

    @Test
    fun `extracts token from catweather scheme`() {
        val token = parsePairingToken("catweather://pair?token=weath2")

        assertEquals("WEATH2", token)
    }

    @Test
    fun `extracts token from code parameter`() {
        val token = parsePairingToken("https://example.com/pair?code=code239")

        assertEquals("CODE239", token)
    }

    @Test
    fun `extracts token from PAIR prefix`() {
        val token = parsePairingToken("PAIR: prefix7")

        assertEquals("PREFIX7", token)
    }

    @Test
    fun `extracts token from url path`() {
        val token = parsePairingToken("https://example.com/pairing/path88")

        assertEquals("PATH88", token)
    }

    @Test
    fun `returns null for invalid input`() {
        val token = parsePairingToken("https://example.com/?foo=bar")

        assertNull(token)
    }

    @Test
    fun `returns null when parameter is invalid`() {
        val token = parsePairingToken("https://example.com/pair?token=invalid1")

        assertNull(token)
    }

    @Test
    fun `returns null when token is too short`() {
        val token = parsePairingToken("https://example.com/pair?token=abc2")

        assertNull(token)
    }

    @Test
    fun `returns null when token contains invalid characters`() {
        val token = parsePairingToken("PAIR: abcd-12")

        assertNull(token)
    }
}
