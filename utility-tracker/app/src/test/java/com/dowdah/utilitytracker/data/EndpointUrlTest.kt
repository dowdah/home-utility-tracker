package com.dowdah.utilitytracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class EndpointUrlTest {
    @Test fun `normalizes a root URL and rejects credential or path URLs`() {
        assertEquals("https://meter.example.invalid:8443", normalizeEndpointUrl(" https://meter.example.invalid:8443/ "))
        listOf("ftp://meter.example.invalid", "https://user@meter.example.invalid", "https://meter.example.invalid/api").forEach { value ->
            try { normalizeEndpointUrl(value); fail("Expected $value to be rejected") } catch (_: EndpointValidationException) { }
        }
    }
}
