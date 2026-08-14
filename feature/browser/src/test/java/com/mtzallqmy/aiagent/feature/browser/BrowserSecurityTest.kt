package com.mtzallqmy.aiagent.feature.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSecurityTest {
    @Test
    fun `url policy accepts public https and blocks dangerous targets`() {
        assertTrue(BrowserUrlPolicy.isAllowed("https://example.com/path?q=1"))
        assertFalse(BrowserUrlPolicy.isAllowed("http://example.com"))
        assertFalse(BrowserUrlPolicy.isAllowed("https://user:password@example.com"))
        assertFalse(BrowserUrlPolicy.isAllowed("https://127.0.0.1/admin"))
        assertFalse(BrowserUrlPolicy.isAllowed("https://192.168.1.2"))
        assertFalse(BrowserUrlPolicy.isAllowed("javascript:alert(1)"))
        assertFalse(BrowserUrlPolicy.isAllowed("file:///data/data/private"))
    }

    @Test
    fun `verification fails when any expected observation is absent`() {
        val state = BrowserState(
            tabId = "tab",
            url = "https://example.com/account",
            title = "Account",
            accessibleTree = "Sign out",
        )

        assertTrue(
            verifyState(
                state,
                BrowserExpectation(urlContains = "example.com", titleContains = "Account", textContains = "Sign out"),
            ).passed,
        )
        assertFalse(verifyState(state, BrowserExpectation(elementPresent = "#billing-form")).passed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `remote coroutine cancellation cancels the Browser Use run`() = runTest {
        val transport = FakeTransport(terminal = false)
        val backend = BrowserUseRemote(transport, pollIntervalMs = 1_000L)
        val task = launch { backend.runJob("Observe the page") }

        runCurrent()
        task.cancel()
        task.join()

        assertTrue(transport.cancelled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `remote timeout cancels the run and reports timed out`() = runTest {
        val transport = FakeTransport(terminal = false)
        val backend = BrowserUseRemote(transport, pollIntervalMs = 1_000L)

        val job = backend.runJob("Observe the page", timeoutMs = 2_500L)

        assertEquals(RemoteBrowserJobStatus.TIMED_OUT, job.status)
        assertTrue(transport.cancelled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `remote verification uses observed result and never unconditional success`() = runTest {
        val transport = FakeTransport(terminal = true)
        val backend = BrowserUseRemote(transport, pollIntervalMs = 1L)

        val verification = backend.verify(BrowserExpectation(textContains = "Approved"))
        advanceUntilIdle()

        assertFalse(verification.passed)
        assertTrue(verification.observed.url == "https://example.com/")
    }

    private class FakeTransport(private val terminal: Boolean) : BrowserUseTransport {
        var cancelled = false

        override suspend fun isConfigured(): Boolean = true
        override suspend fun healthCheck(): Boolean = true

        override suspend fun create(task: String, sessionId: String?): RemoteBrowserJob = RemoteBrowserJob(
            id = "11111111-1111-1111-1111-111111111111",
            sessionId = "22222222-2222-2222-2222-222222222222",
            status = RemoteBrowserJobStatus.QUEUED,
        )

        override suspend fun status(runId: String): RemoteBrowserJobStatus =
            if (terminal) RemoteBrowserJobStatus.COMPLETED else RemoteBrowserJobStatus.RUNNING

        override suspend fun result(runId: String): RemoteBrowserJob = RemoteBrowserJob(
            id = runId,
            sessionId = "22222222-2222-2222-2222-222222222222",
            status = RemoteBrowserJobStatus.COMPLETED,
            result = """{"url":"https://example.com/","title":"Example","text":"Untrusted page text"}""",
        )

        override suspend fun cancel(runId: String): Boolean {
            cancelled = true
            return true
        }
    }
}
