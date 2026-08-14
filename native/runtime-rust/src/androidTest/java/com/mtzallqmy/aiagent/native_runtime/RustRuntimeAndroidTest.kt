package com.mtzallqmy.aiagent.native_runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RustRuntimeAndroidTest {
    private lateinit var client: RustRuntimeClient

    @Before
    fun connectToIsolatedRuntime() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        client = RustRuntimeClient(context)
        client.connect()
    }

    @After
    fun disconnectFromIsolatedRuntime() {
        client.close()
    }

    @Test
    fun capabilitiesReportAndroidAndRustProcessIsolation() {
        val capabilities = client.capabilities()
        assertTrue(capabilities.androidIsolatedProcess)
        assertTrue(capabilities.rustProcessBoundary)
        assertFalse(capabilities.environmentInheritance)
        assertTrue(capabilities.explicitWorkingDirectoryFd)
    }

    @Test
    fun validCommandTraversesBinderJniRustAndChildProcess() {
        val result = execute(
            RustExecutionRequest(
                program = "/system/bin/sh",
                arguments = listOf("-c", "printf aegis-runtime-ok"),
            ),
        )
        assertEquals("completed", result.status)
        assertEquals(0, result.exitCode)
        assertEquals("aegis-runtime-ok", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun invalidExecutableIsRejectedBeforeProcessCreation() {
        val start = client.start(RustExecutionRequest(program = "/system/bin/id"))
        assertEquals("error", start.status)
        assertNull(start.executionId)
        assertTrue(start.error.orEmpty().contains("allowlist", ignoreCase = true))
    }

    @Test
    fun invalidEnvironmentVariableIsRejected() {
        val start = client.start(
            RustExecutionRequest(
                program = "/system/bin/sh",
                arguments = listOf("-c", "true"),
                environment = mapOf("API_KEY" to "must-not-cross-boundary"),
            ),
        )
        assertEquals("error", start.status)
        assertNull(start.executionId)
        assertTrue(start.error.orEmpty().contains("environment", ignoreCase = true))
    }

    @Test
    fun processTimeoutIsEnforced() {
        val result = execute(
            RustExecutionRequest(
                program = "/system/bin/sh",
                arguments = listOf("-c", "sleep 2"),
                timeoutMs = 50,
            ),
        )
        assertEquals("timed_out", result.status)
        assertNull(result.exitCode)
        assertTrue(result.error.orEmpty().contains("timeout", ignoreCase = true))
    }

    @Test
    fun cancellationStopsActiveChildProcess() {
        val start = client.start(
            RustExecutionRequest(
                program = "/system/bin/sh",
                arguments = listOf("-c", "sleep 10"),
                timeoutMs = 15_000,
            ),
        )
        val executionId = requireExecutionId(start)
        try {
            assertTrue(client.cancel(executionId))
            val result = client.awaitResult(executionId, 5_000)
            assertEquals("cancelled", result.status)
            assertNull(result.exitCode)
        } finally {
            client.release(executionId)
        }
    }

    @Test
    fun stdoutAndStderrAreIndependentlyBounded() {
        val stdoutResult = execute(
            RustExecutionRequest(
                program = "/system/bin/sh",
                arguments = listOf("-c", "yes x | head -c 4096"),
                stdoutLimitBytes = 128,
                stderrLimitBytes = 128,
            ),
        )
        val stderrResult = execute(
            RustExecutionRequest(
                program = "/system/bin/sh",
                arguments = listOf("-c", "yes e | head -c 4096 >&2"),
                stdoutLimitBytes = 128,
                stderrLimitBytes = 128,
            ),
        )
        assertEquals(128, stdoutResult.stdout.toByteArray().size)
        assertTrue(stdoutResult.stdoutTruncated)
        assertEquals(128, stderrResult.stderr.toByteArray().size)
        assertTrue(stderrResult.stderrTruncated)
    }

    @Test
    fun concurrentExecutionsRemainIndependent() {
        val starts = (1..4).map { index ->
            client.start(
                RustExecutionRequest(
                    program = "/system/bin/sh",
                    arguments = listOf("-c", "sleep 1; printf run-$index"),
                    timeoutMs = 5_000,
                ),
            )
        }
        val ids = starts.map(::requireExecutionId)
        try {
            val results = ids.map { client.awaitResult(it, 5_000) }
            assertTrue(results.all { it.status == "completed" && it.exitCode == 0 })
            assertEquals(setOf("run-1", "run-2", "run-3", "run-4"), results.map { it.stdout }.toSet())
        } finally {
            ids.forEach(client::release)
        }
    }

    @Test
    fun isolatedRuntimeCannotReadArbitraryAppPrivateFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = "private-${UUID.randomUUID()}"
        val privateFile = context.filesDir.resolve("runtime-private-canary.txt")
        privateFile.writeText(marker)
        try {
            val result = execute(
                RustExecutionRequest(
                    program = "/system/bin/sh",
                    arguments = listOf("-c", "cat '${privateFile.absolutePath}'"),
                ),
            )
            assertTrue(result.exitCode != 0)
            assertFalse(result.stdout.contains(marker))
            assertFalse(result.stderr.contains(marker))
        } finally {
            privateFile.delete()
        }
    }

    @Test
    fun explicitCloseRequiresReconnectAndReconnectRestoresExecution() = runBlocking {
        client.close()
        assertTrue(runCatching { client.capabilities() }.isFailure)
        client.connect()
        val result = execute(
            RustExecutionRequest(
                program = "/system/bin/sh",
                arguments = listOf("-c", "printf reconnected"),
            ),
        )
        assertEquals("completed", result.status)
        assertEquals(0, result.exitCode)
        assertEquals("reconnected", result.stdout)
    }

    private fun execute(request: RustExecutionRequest): RustExecutionResult {
        val start = client.start(request)
        val executionId = requireExecutionId(start)
        return try {
            client.awaitResult(executionId, 10_000)
        } finally {
            client.release(executionId)
        }
    }

    private fun requireExecutionId(start: RustStartResponse): String {
        assertEquals("started", start.status)
        assertNotNull(start.executionId)
        return requireNotNull(start.executionId)
    }
}
