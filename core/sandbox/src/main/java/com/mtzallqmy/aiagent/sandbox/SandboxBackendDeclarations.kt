package com.mtzallqmy.aiagent.sandbox

/**
 * Capability-only declarations for backends implemented in their owning modules.
 * They prevent callers from treating app, isolated-process, Rust, PRoot, and
 * remote execution as equivalent isolation levels.
 */
object SandboxBackendDeclarations {
    val androidAppSandbox = SandboxCapabilities(
        isolationLevel = SandboxIsolationLevel.APP_SANDBOX,
        processLifecycle = true,
        timeout = true,
        cancellation = true,
        boundedOutput = true,
        stdinAtStart = true,
        streamingStdin = false,
        environmentAllowlist = true,
        workingDirectory = true,
        fileTransfer = false,
        containerIsolation = false,
        networkIsolation = false,
        filesystemNamespaces = false,
    )

    val isolatedProcess = androidAppSandbox.copy(
        isolationLevel = SandboxIsolationLevel.ANDROID_ISOLATED_PROCESS,
    )

    val rustRuntime = isolatedProcess.copy(
        isolationLevel = SandboxIsolationLevel.RUST_PROCESS_BOUNDARY,
    )

    val remote = SandboxCapabilities(
        isolationLevel = SandboxIsolationLevel.REMOTE_SANDBOX,
        processLifecycle = true,
        timeout = true,
        cancellation = true,
        boundedOutput = true,
        stdinAtStart = true,
        streamingStdin = false,
        environmentAllowlist = false,
        workingDirectory = true,
        fileTransfer = true,
        containerIsolation = false,
        networkIsolation = false,
        filesystemNamespaces = false,
    )
}
