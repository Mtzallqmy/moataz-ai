package com.mtzallqmy.aiagent.sandbox

import com.mtzallqmy.aiagent.sandbox.termux.PREFIX
import com.mtzallqmy.aiagent.sandbox.termux.TERMUX_HOME
import com.mtzallqmy.aiagent.sandbox.termux.TermuxCommand
import com.mtzallqmy.aiagent.sandbox.termux.TermuxCommandResult
import com.mtzallqmy.aiagent.sandbox.termux.TermuxCommandTransport
import kotlinx.coroutines.TimeoutCancellationException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Opt-in PRoot backend using the official Termux RUN_COMMAND boundary and
 * proot-distro. PRoot is userspace path/syscall translation, not a container:
 * it provides no PID/network/IPC namespaces, cgroups, or seccomp.
 */
class ProotLinuxBackend(
    private val transport: TermuxCommandTransport,
    private val installationOptIn: suspend () -> Boolean,
    private val allowedImages: Set<String> = DEFAULT_IMAGES,
    private val guestEnvironmentAllowlist: Set<String> = DEFAULT_ENVIRONMENT,
) : SandboxBackend {
    override val id: String = "proot_linux"
    override val capabilities = SandboxCapabilities(
        isolationLevel = SandboxIsolationLevel.PROOT_USERSPACE,
        processLifecycle = true,
        timeout = true,
        cancellation = true,
        boundedOutput = true,
        stdinAtStart = true,
        streamingStdin = false,
        environmentAllowlist = true,
        workingDirectory = true,
        fileTransfer = true,
        containerIsolation = false,
        networkIsolation = false,
        filesystemNamespaces = false,
    )

    private val sessions = ConcurrentHashMap<String, SandboxSession>()

    override suspend fun healthCheck(): SandboxHealth {
        val transportHealth = transport.health()
        if (!transportHealth.packageInstalled || !transportHealth.permissionGranted || !transportHealth.serviceResolvable) {
            return SandboxHealth(
                available = false,
                message = "Termux integration is not ready",
                details = mapOf(
                    "packageInstalled" to transportHealth.packageInstalled.toString(),
                    "runCommandPermission" to transportHealth.permissionGranted.toString(),
                    "runCommandService" to transportHealth.serviceResolvable.toString(),
                    "requiresAllowExternalApps" to "true",
                ),
            )
        }
        val result = runCatching {
            transport.execute(
                TermuxCommand(
                    executable = PROOT_DISTRO,
                    arguments = listOf("--version"),
                    timeoutMs = 15_000L,
                ),
            )
        }.getOrElse {
            return SandboxHealth(false, it.message ?: "proot-distro health check failed")
        }
        return SandboxHealth(
            available = result.success,
            message = if (result.success) "proot-distro is available" else result.stderr.ifBlank { result.internalErrorMessage },
            details = mapOf(
                "isolation" to "proot-userspace",
                "containerIsolation" to "false",
                "networkIsolation" to "false",
                "version" to result.stdout.trim().take(200),
            ),
        )
    }

    override suspend fun install(spec: SandboxEnvironmentSpec): SandboxExecResult {
        validateEnvironmentId(spec.id)
        require(spec.image in allowedImages) { "Image is not in the PRoot installation allowlist" }
        check(installationOptIn()) { "PRoot installation requires explicit user opt-in" }

        val health = healthCheck()
        if (!health.available) {
            val bootstrap = executeHost(
                executable = PKG,
                arguments = listOf("install", "-y", "proot-distro"),
                timeoutMs = 15 * 60 * 1_000L,
            )
            if (bootstrap.exitCode != 0) return bootstrap
        }
        val arguments = buildList {
            add("install")
            add("--name")
            add(spec.id)
            spec.architecture?.let {
                require(it in ALLOWED_ARCHITECTURES) { "Unsupported PRoot architecture" }
                add("--architecture")
                add(it)
            }
            add(spec.image)
        }
        return executeHost(PROOT_DISTRO, arguments, 15 * 60 * 1_000L)
    }

    override suspend fun start(environmentId: String): SandboxSession {
        validateEnvironmentId(environmentId)
        val probe = executeHost(
            PROOT_DISTRO,
            listOf("login", "--minimal", environmentId, "--", "/bin/true"),
            30_000L,
        )
        check(probe.exitCode == 0) { probe.stderr.ifBlank { "Unable to start PRoot environment" } }
        return SandboxSession(
            id = UUID.randomUUID().toString(),
            environmentId = environmentId,
            startedAtEpochMs = System.currentTimeMillis(),
        ).also { sessions[it.id] = it }
    }

    override suspend fun exec(session: SandboxSession, request: SandboxExecRequest): SandboxExecResult {
        require(sessions[session.id] == session) { "PRoot session is not active" }
        require(request.argv.isNotEmpty()) { "Command argv must not be empty" }
        require(request.argv.size <= 64) { "Too many guest command arguments" }
        validateGuestPath(request.cwd)
        validateEnvironment(request.environment)
        val arguments = buildList {
            add("login")
            add("--minimal")
            add("--work-dir")
            add(request.cwd)
            request.environment.toSortedMap().forEach { (key, value) ->
                add("--env")
                add("$key=$value")
            }
            add(session.environmentId)
            add("--")
            addAll(request.argv)
        }
        val started = System.currentTimeMillis()
        val result = try {
            transport.execute(
                TermuxCommand(
                    executable = PROOT_DISTRO,
                    arguments = arguments,
                    stdin = request.stdin,
                    timeoutMs = request.timeoutMs,
                ),
            ).toSandboxResult(started, request.stdoutLimitChars, request.stderrLimitChars)
        } catch (_: TimeoutCancellationException) {
            runCatching { executeHost(PROOT_DISTRO, listOf("kill", session.environmentId), 15_000L) }
            SandboxExecResult(
                exitCode = -1,
                stdout = "",
                stderr = "PRoot execution timed out",
                stdoutTruncated = false,
                stderrTruncated = false,
                durationMs = System.currentTimeMillis() - started,
                timedOut = true,
            )
        }
        return result
    }

    override suspend fun transfer(transfer: SandboxTransfer): SandboxExecResult {
        validateEnvironmentId(transfer.environmentId)
        val (source, destination) = when (transfer) {
            is SandboxTransfer.IntoEnvironment -> {
                validateTermuxHostPath(transfer.termuxHostPath)
                validateGuestPath(transfer.guestPath)
                transfer.termuxHostPath to "${transfer.environmentId}:${transfer.guestPath}"
            }
            is SandboxTransfer.OutOfEnvironment -> {
                validateGuestPath(transfer.guestPath)
                validateTermuxHostPath(transfer.termuxHostPath)
                "${transfer.environmentId}:${transfer.guestPath}" to transfer.termuxHostPath
            }
        }
        return executeHost(PROOT_DISTRO, listOf("copy", "--recursive", source, destination), 5 * 60 * 1_000L)
    }

    override suspend fun stop(session: SandboxSession): SandboxExecResult {
        sessions.remove(session.id)
        return executeHost(PROOT_DISTRO, listOf("kill", session.environmentId), 30_000L)
    }

    override suspend fun reset(environmentId: String): SandboxExecResult {
        validateEnvironmentId(environmentId)
        check(installationOptIn()) { "PRoot reset requires explicit user opt-in because it deletes guest data" }
        sessions.entries.removeIf { it.value.environmentId == environmentId }
        executeHost(PROOT_DISTRO, listOf("kill", environmentId), 30_000L)
        return executeHost(PROOT_DISTRO, listOf("reset", environmentId), 15 * 60 * 1_000L)
    }

    private suspend fun executeHost(
        executable: String,
        arguments: List<String>,
        timeoutMs: Long,
    ): SandboxExecResult {
        val started = System.currentTimeMillis()
        return transport.execute(
            TermuxCommand(
                executable = executable,
                arguments = arguments,
                workingDirectory = TERMUX_HOME,
                timeoutMs = timeoutMs,
            ),
        ).toSandboxResult(started, 100_000, 100_000)
    }

    private fun validateEnvironmentId(value: String) {
        require(value.matches(ENVIRONMENT_ID)) { "Invalid PRoot environment id" }
    }

    private fun validateEnvironment(environment: Map<String, String>) {
        require(environment.size <= guestEnvironmentAllowlist.size) { "Too many guest environment variables" }
        environment.forEach { (key, value) ->
            require(key in guestEnvironmentAllowlist) { "Guest environment key is not allowed: $key" }
            require(!SECRET_KEY.containsMatchIn(key)) { "Secret-like guest environment keys are forbidden" }
            require(value.toByteArray().size <= 4 * 1024) { "Guest environment value is too large" }
        }
    }

    private fun validateGuestPath(path: String) {
        require(path.startsWith('/')) { "Guest paths must be absolute" }
        require(path.split('/').none { it == ".." }) { "Guest path traversal is forbidden" }
        require('\u0000' !in path) { "Guest path contains NUL" }
    }

    private fun validateTermuxHostPath(path: String) {
        require(path.startsWith("$TERMUX_HOME/") || path.startsWith("$PREFIX/tmp/")) {
            "Host transfer paths must stay in Termux HOME or PREFIX/tmp"
        }
        require(path.split('/').none { it == ".." }) { "Host path traversal is forbidden" }
    }

    private fun TermuxCommandResult.toSandboxResult(
        started: Long,
        stdoutLimit: Int,
        stderrLimit: Int,
    ): SandboxExecResult {
        val stdoutBound = stdout.take(stdoutLimit)
        val stderrWithInternal = buildString {
            append(stderr)
            if (internalErrorCode != -1 && internalErrorMessage.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(internalErrorMessage)
            }
        }.take(stderrLimit)
        return SandboxExecResult(
            exitCode = if (internalErrorCode == -1) exitCode else -1,
            stdout = stdoutBound,
            stderr = stderrWithInternal,
            stdoutTruncated = stdoutOriginalLength > stdoutBound.length || stdout.length > stdoutBound.length,
            stderrTruncated = stderrOriginalLength > stderrWithInternal.length || stderr.length > stderrWithInternal.length,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    companion object {
        private const val PROOT_DISTRO = "$PREFIX/bin/proot-distro"
        private const val PKG = "$PREFIX/bin/pkg"
        private val ENVIRONMENT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
        private val SECRET_KEY = Regex("(?i)(key|token|secret|password|credential|auth)")
        private val ALLOWED_ARCHITECTURES = setOf("aarch64", "arm", "i686", "x86_64", "riscv64")
        val DEFAULT_IMAGES = setOf("alpine:3.21", "ubuntu:24.04", "debian:bookworm")
        val DEFAULT_ENVIRONMENT = setOf("LANG", "LC_ALL", "TZ", "TERM", "COLUMNS", "LINES")
    }
}
