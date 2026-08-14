package com.mtzallqmy.aiagent.network

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeHttpClientTest {
    @Test
    fun `private and reserved address ranges are blocked`() {
        listOf(
            "127.0.0.1",
            "10.0.0.1",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.1.2",
            "100.64.0.1",
            "198.18.0.1",
            "::1",
            "fc00::1",
            "fe80::1",
        ).forEach { host -> assertTrue(host, SafeHttpClient.isPrivate(host)) }
    }

    @Test
    fun `public 172 ranges are not overblocked`() {
        assertFalse(SafeHttpClient.isPrivate("172.15.1.1"))
        assertFalse(SafeHttpClient.isPrivate("172.32.1.1"))
    }

    @Test
    fun `normalization rejects credentials and local destinations`() {
        assertNull(SafeHttpClient.normalizeUrl("https://user:pass@example.com/v1"))
        assertNull(SafeHttpClient.normalizeUrl("http://localhost:8080/v1"))
        assertNull(SafeHttpClient.normalizeUrl("file:///tmp/a"))
        assertTrue(SafeHttpClient.normalizeUrl("https://example.com/v1")!!.startsWith("https://example.com"))
    }

    @Test
    fun `address classifier blocks unique local ipv6`() {
        assertTrue(SafeHttpClient.isBlockedAddress(InetAddress.getByName("fd12:3456::1")))
    }
}
