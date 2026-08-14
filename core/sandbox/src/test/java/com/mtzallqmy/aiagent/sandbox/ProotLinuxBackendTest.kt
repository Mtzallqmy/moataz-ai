package com.mtzallqmy.aiagent.sandbox

import com.mtzallqmy.aiagent.sandbox.termux.TermuxCommand
import com.mtzallqmy.aiagent.sandbox.termux.TermuxCommandResult
import com.mtzallqmy.aiagent.sandbox.termux.TermuxCommandTransport
import com.mtzallqmy.aiagent.sandbox.termux.TermuxTransportHealth
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotLinuxBackendTest {
    @Test
    fun capabilitiesNeverClaimContainerIsolation() {
        val backend = ProotLinuxBackend(FakeTransport(), installationOptIn = { false })

        assertEquals(SandboxIsolationLevel.PROOT_USERSPACE, backend.capabilities.isolationLevel)
        assertFalse(backend.capabilities.containerIsolation)
        assertFalse(backend.capabilities.networkIsolation)
        assertFalse(backend.capabilities.filesystemNamespaces)
        assertTrue(backend.capabilities.stdinAtStart)
        assertFalse(backend.capabilities.streamingStdin)
    }

    @Test(expected = IllegalStateException::class)
    fun installIsBlockedWithoutExplicitOptIn() = runBlocking {
        ProotLinuxBackend(FakeTransport(), installationOptIn = { false }).install(
            SandboxEnvironmentSpec("test", "alpine:3.21"),
        )
        Unit
    }

    @Test
    fun installBootstrapsAndUsesAllowlistedOciImage() = runBlocking {
        val transport = FakeTransport(
            responder = { command ->
                if (command.arguments == listOf("--version")) failure("not installed") else success()
            },
        )
        val backend = ProotLinuxBackend(transport, installationOptIn = { true })

        val result = backend.install(SandboxEnvironmentSpec("build", "alpine:3.21", "aarch64"))

        assertEquals(0, result.exitCode)
        assertEquals(listOf("install", "-y", "proot-distro"), transport.commands[1].arguments)
        assertEquals(
            listOf("install", "--name", "build", "--architecture", "aarch64", "alpine:3.21"),
            transport.commands[2].arguments,
        )
    }

    @Test
    fun execPassesArgvCwdEnvironmentAndStdinWithoutStringConcatenation() = runBlocking {
        val transport = FakeTransport()
        val backend = ProotLinuxBackend(transport, installationOptIn = { true })
        val session = backend.start("dev")

        val result = backend.exec(
            session,
            SandboxExecRequest(
                argv = listOf("/usr/bin/printf", "%s", "hello world"),
                stdin = "input",
                cwd = "/workspace/project",
                environment = mapOf("LANG" to "C.UTF-8"),
            ),
        )

        assertEquals(0, result.exitCode)
        val command = transport.commands.last()
        assertEquals("input", command.stdin)
        assertTrue(command.arguments.containsAll(listOf("--minimal", "--work-dir", "--env", "LANG=C.UTF-8", "/workspace/project")))
        assertEquals(listOf("/usr/bin/printf", "%s", "hello world"), command.arguments.takeLast(3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun secretEnvironmentIsRejected() = runBlocking {
        val backend = ProotLinuxBackend(FakeTransport(), installationOptIn = { true })
        val session = backend.start("dev")
        backend.exec(
            session,
            SandboxExecRequest(
                argv = listOf("/bin/true"),
                environment = mapOf("OPENAI_API_KEY" to "secret"),
            ),
        )
        Unit
    }

    @Test
    fun transferUsesProotCopyWithConfinedHostPath() = runBlocking {
        val transport = FakeTransport()
        val backend = ProotLinuxBackend(transport, installationOptIn = { true })

        backend.transfer(
            SandboxTransfer.IntoEnvironment(
                environmentId = "dev",
                termuxHostPath = "/data/data/com.termux/files/home/import/file.txt",
                guestPath = "/root/file.txt",
            ),
        )

        assertEquals(
            listOf(
                "copy",
                "--recursive",
                "/data/data/com.termux/files/home/import/file.txt",
                "dev:/root/file.txt",
            ),
            transport.commands.last().arguments,
        )
    }

    private class FakeTransport(
        private val transportHealth: TermuxTransportHealth = TermuxTransportHealth(true, true, true),
        private val responder: suspend (TermuxCommand) -> TermuxCommandResult = { success() },
    ) : TermuxCommandTransport {
        val commands = mutableListOf<TermuxCommand>()

        override fun health(): TermuxTransportHealth = transportHealth

        override suspend fun execute(command: TermuxCommand): TermuxCommandResult {
            commands += command
            return responder(command)
        }
    }

    private companion object {
        fun success(stdout: String = "ok") = TermuxCommandResult(
            exitCode = 0,
            stdout = stdout,
            stderr = "",
            stdoutOriginalLength = stdout.length,
            stderrOriginalLength = 0,
            internalErrorCode = -1,
            internalErrorMessage = "",
        )

        fun failure(message: String) = TermuxCommandResult(
            exitCode = 1,
            stdout = "",
            stderr = message,
            stdoutOriginalLength = 0,
            stderrOriginalLength = message.length,
            internalErrorCode = -1,
            internalErrorMessage = "",
        )
    }
}
