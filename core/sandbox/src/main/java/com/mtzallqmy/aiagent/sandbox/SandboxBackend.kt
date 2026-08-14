package com.mtzallqmy.aiagent.sandbox

enum class SandboxIsolationLevel {
    APP_SANDBOX,
    ANDROID_ISOLATED_PROCESS,
    RUST_PROCESS_BOUNDARY,
    PROOT_USERSPACE,
    REMOTE_SANDBOX,
}

data class SandboxCapabilities(
    val isolationLevel: SandboxIsolationLevel,
    val processLifecycle: Boolean,
    val timeout: Boolean,
    val cancellation: Boolean,
    val boundedOutput: Boolean,
    val stdinAtStart: Boolean,
    val streamingStdin: Boolean,
    val environmentAllowlist: Boolean,
    val workingDirectory: Boolean,
    val fileTransfer: Boolean,
    val containerIsolation: Boolean,
    val networkIsolation: Boolean,
    val filesystemNamespaces: Boolean,
)

data class SandboxHealth(
    val available: Boolean,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

data class SandboxEnvironmentSpec(
    val id: String,
    val image: String,
    val architecture: String? = null,
)

data class SandboxSession(
    val id: String,
    val environmentId: String,
    val startedAtEpochMs: Long,
)

data class SandboxExecRequest(
    val argv: List<String>,
    val stdin: String? = null,
    val cwd: String = "/root",
    val environment: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 60_000L,
    val stdoutLimitChars: Int = 100_000,
    val stderrLimitChars: Int = 100_000,
)

data class SandboxExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val durationMs: Long,
    val timedOut: Boolean = false,
)

sealed interface SandboxTransfer {
    val environmentId: String

    data class IntoEnvironment(
        override val environmentId: String,
        val termuxHostPath: String,
        val guestPath: String,
    ) : SandboxTransfer

    data class OutOfEnvironment(
        override val environmentId: String,
        val guestPath: String,
        val termuxHostPath: String,
    ) : SandboxTransfer
}

interface SandboxBackend {
    val id: String
    val capabilities: SandboxCapabilities

    suspend fun healthCheck(): SandboxHealth
    suspend fun install(spec: SandboxEnvironmentSpec): SandboxExecResult
    suspend fun start(environmentId: String): SandboxSession
    suspend fun exec(session: SandboxSession, request: SandboxExecRequest): SandboxExecResult
    suspend fun transfer(transfer: SandboxTransfer): SandboxExecResult
    suspend fun stop(session: SandboxSession): SandboxExecResult
    suspend fun reset(environmentId: String): SandboxExecResult
}

class SandboxBackendRegistry(backends: Iterable<SandboxBackend> = emptyList()) {
    private val values = linkedMapOf<String, SandboxBackend>()

    init { backends.forEach(::register) }

    @Synchronized
    fun register(backend: SandboxBackend) {
        check(values.putIfAbsent(backend.id, backend) == null) { "Sandbox backend already registered: ${backend.id}" }
    }

    @Synchronized
    fun get(id: String): SandboxBackend? = values[id]

    @Synchronized
    fun list(): List<SandboxBackend> = values.values.toList()
}
