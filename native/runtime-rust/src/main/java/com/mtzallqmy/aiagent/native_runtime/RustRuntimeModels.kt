package com.mtzallqmy.aiagent.native_runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RustExecutionRequest(
    val program: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val stdin: String? = null,
    @SerialName("timeout_ms") val timeoutMs: Long = 60_000L,
    @SerialName("stdout_limit_bytes") val stdoutLimitBytes: Int = 128 * 1024,
    @SerialName("stderr_limit_bytes") val stderrLimitBytes: Int = 64 * 1024,
)

@Serializable
data class RustStartResponse(
    val status: String,
    @SerialName("execution_id") val executionId: String? = null,
    @SerialName("process_id") val processId: Long? = null,
    val error: String? = null,
)

@Serializable
data class RustExecutionResult(
    val status: String,
    @SerialName("execution_id") val executionId: String,
    @SerialName("exit_code") val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    @SerialName("stdout_truncated") val stdoutTruncated: Boolean = false,
    @SerialName("stderr_truncated") val stderrTruncated: Boolean = false,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val error: String? = null,
)

@Serializable
data class RustRuntimeCapabilities(
    @SerialName("isolation_level") val isolationLevel: String,
    @SerialName("android_isolated_process") val androidIsolatedProcess: Boolean,
    @SerialName("rust_process_boundary") val rustProcessBoundary: Boolean,
    @SerialName("container_isolation") val containerIsolation: Boolean,
    @SerialName("filesystem_namespaces") val filesystemNamespaces: Boolean,
    @SerialName("environment_inheritance") val environmentInheritance: Boolean,
    @SerialName("explicit_working_directory_fd") val explicitWorkingDirectoryFd: Boolean,
)
