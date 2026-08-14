package com.mtzallqmy.aiagent.feature.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSecurityPolicyTest {
    @Test
    fun `browser policy preserves strict main URL protections`() {
        assertTrue(BrowserSecurityPolicy.isAllowedUrl("https://example.com"))
        assertTrue(BrowserSecurityPolicy.isAllowedUrl("about:blank"))
        assertFalse(BrowserSecurityPolicy.isAllowedUrl("http://example.com"))
        assertFalse(BrowserSecurityPolicy.isAllowedUrl("https://127.0.0.1/admin"))
        assertFalse(BrowserSecurityPolicy.isAllowedUrl("https://192.168.1.2"))
        assertFalse(BrowserSecurityPolicy.isAllowedUrl("file:///sdcard/private.txt"))
        assertFalse(BrowserSecurityPolicy.isAllowedUrl("javascript:alert(1)"))
        assertFalse(BrowserSecurityPolicy.isAllowedUrl("intent://settings"))
        assertFalse(BrowserSecurityPolicy.isAllowedUrl("content://private/item"))
    }

    @Test
    fun `selectors and text are bounded`() {
        assertTrue(BrowserSecurityPolicy.isSafeSelector("button[data-testid='save']"))
        assertFalse(BrowserSecurityPolicy.isSafeSelector(""))
        assertFalse(BrowserSecurityPolicy.isSafeSelector("a\u0000b"))
        assertFalse(BrowserSecurityPolicy.isSafeSelector("x".repeat(2_049)))
        assertFalse(BrowserSecurityPolicy.isSafeText("x".repeat(64 * 1024 + 1)))
    }

    @Test
    fun `form values are removed from snapshots`() {
        val raw = """{
          "url":"https://example.test/login",
          "title":"Login",
          "nodes":[
            {"index":0,"tag":"INPUT","id":"password","cls":"","text":"super-secret","href":"","type":"password","disabled":false},
            {"index":1,"tag":"P","id":"","cls":"","text":"Public text","href":"","type":"","disabled":false}
          ]
        }""".trimIndent()
        val sanitized = BrowserSnapshotSanitizer.sanitize(raw)
        assertFalse(sanitized.contains("super-secret"))
        assertTrue(sanitized.contains("[form-field]"))
        assertTrue(sanitized.contains("Public text"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed snapshot is rejected`() {
        BrowserSnapshotSanitizer.sanitize("not-json")
    }
}
