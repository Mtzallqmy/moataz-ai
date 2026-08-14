package com.mtzallqmy.aiagent.common

/** Typed, user-safe errors. Stack traces are never shown directly to the user. */
sealed class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    data class NetworkError(val reason: String) : AgentException("Network error: $reason")
    data class AuthenticationError(val reason: String) : AgentException("Authentication failed: $reason")
    data class RateLimitError(val retryAfterSeconds: Long? = null) : AgentException("Rate limit reached" + (retryAfterSeconds?.let { " (retry after $it s)" } ?: ""))
    data class ProviderError(val statusCode: Int, val reason: String) : AgentException("Provider error ($statusCode): $reason")
    data class ModelNotFoundError(val modelId: String) : AgentException("Model not found: $modelId")
    data class PermissionDeniedError(val permission: String) : AgentException("Permission denied: $permission")
    data class ApprovalRequiredError(val toolName: String) : AgentException("Approval required for tool: $toolName")
    data class ApprovalDeniedError(val toolName: String) : AgentException("Approval denied for tool: $toolName")
    data class CapabilityUnavailableError(val capabilityId: String) : AgentException("Capability unavailable: $capabilityId")
    data class ToolTimeoutError(val toolId: String, val timeoutMs: Long) : AgentException("Tool $toolId timed out after $timeoutMs ms")
    data class ToolCancelledError(val toolId: String) : AgentException("Tool $toolId was cancelled")
    data class ToolExecutionError(val toolId: String, val reason: String) : AgentException("Tool $toolId failed: $reason")
    data class BrowserError(val reason: String) : AgentException("Browser error: $reason")
    data class SandboxError(val reason: String) : AgentException("Sandbox error: $reason")
    data class McpError(val reason: String) : AgentException("MCP error: $reason")
    data class SshError(val reason: String) : AgentException("SSH error: $reason")
}
