package com.mtzallqmy.aiagent.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretSanitizerTest {

    @Test
    fun `sanitizes OpenAI style keys`() {
        val input = "sk-1234567890abcdefghijklmnopqrst"
        val out = SecretSanitizer.sanitize(input)
        assertTrue("prefix preserved: $out", out.startsWith("sk-1234"))
        assertTrue("suffix preserved: $out", out.endsWith("st"))
        assertTrue("contains mask: $out", out.contains("****"))
        assertFalse("raw secret removed: $out", out.contains(input))
    }

    @Test
    fun `sanitizes GitHub personal access tokens`() {
        val token = "github_pat_PLACEHOLDER_TOKEN_REMOVED"
        val out = SecretSanitizer.sanitize("Bearer $token")
        assertFalse(out.contains(token))
        assertTrue(out.contains("****"))
    }

    @Test
    fun `sanitizes GitHub classic tokens`() {
        val token = "ghp_abcdefghijklmnopqrstuvwxyz1234567890AB"
        val out = SecretSanitizer.sanitize(token)
        assertFalse(out.contains(token))
    }

    @Test
    fun `leaves clean text untouched`() {
        val clean = "Hello world, the temperature is 23 degrees."
        assertEquals(clean, SecretSanitizer.sanitize(clean))
    }

    @Test
    fun `sanitizes JWT style tokens`() {
        val head = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        val payload = "eyJzdWIiOiIxMjM0NDU2Nzg5MCIsIm5hbWUiOiJBZWdpcyBBZ2VudCBPUyB1c2VyIiwiaWF0IjoxNTE2MjM5MDIyfQ"
        val sig = "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val jwt = "$head.$payload.$sig"
        val out = SecretSanitizer.sanitize(jwt)
        assertFalse(out.contains(sig))
    }

    @Test
    fun `handles empty input`() {
        assertEquals("", SecretSanitizer.sanitize(""))
    }

    @Test
    fun `handles multiple secrets in one string`() {
        val input = "key1=sk-1234567890abcdefghijklmnopqrst key2=ghp_abcdefghijklmnopqrstuvwxyz1234567890AB"
        val out = SecretSanitizer.sanitize(input)
        assertTrue(out.contains("****"))
        assertFalse("both secrets masked", out.contains("1234567890abcdefghijklmnopqrst") && out.contains("abcdefghijklmnopqrstuvwxyz1234567890AB"))
    }
}
