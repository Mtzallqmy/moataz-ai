package com.mtzallqmy.aiagent.model

import kotlinx.serialization.Serializable

/**
 * Shared domain models used across all modules.
 */

/** Full agent state machine as specified in the requirements. */
enum class AgentState {
    IDLE,
    THINKING,
    PLANNING,
    WAITING_FOR_TOOL,
    EXECUTING_TOOL,
    WAITING_FOR_APPROVAL,
    OBSERVING,
    REPLANNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/**
 * Normalized provider events. Providers must map their own streaming formats
 * (SSE, Anthropic SSE, Gemini) to these events so the Agent Core is provider-agnostic.
 */
sealed class GenerationEvent {
    data object GenerationStarted : GenerationEvent()
    data class TextDelta(val text: String) : GenerationEvent()
    data class ReasoningSummaryDelta(val summary: String) : GenerationEvent()
    data class ToolCallStarted(val callId: String, val toolName: String) : GenerationEvent()
    data class ToolCallArgumentsDelta(val callId: String, val argsFragment: String) : GenerationEvent()
    data class ToolCallCompleted(val callId: String, val result: String) : GenerationEvent()
    data class Usage(val promptTokens: Int, val completionTokens: Int, val totalCost: Double = 0.0) : GenerationEvent()
    data class GenerationCompleted(val finalText: String) : GenerationEvent()
    data class GenerationFailed(val error: ProviderError) : GenerationEvent()
}

/** Typed errors for the entire platform. */
sealed class ProviderError(message: String, cause: Throwable? = null) : Throwable(message, cause) {
    data class NetworkError(val reason: String) : ProviderError(reason)
    data class AuthenticationError(val reason: String) : ProviderError(reason)
    data class RateLimitError(val retryAfterSeconds: Long? = null) : ProviderError("Rate limited" + (retryAfterSeconds?.let { " ($it s)" } ?: ""))
    data class ProviderError_(val statusCode: Int, val reason: String) : ProviderError("Provider error $statusCode: $reason")
    data class ModelNotFoundError(val modelId: String) : ProviderError("Model not found: $modelId")
    data object StreamingNotSupported : ProviderError("Streaming not supported by this provider")
    data class ConfigurationError(val reason: String) : ProviderError(reason)
    data class CapabilityError(val reason: String) : ProviderError(reason)
    data class RoutingError(val reason: String) : ProviderError(reason)
}

/** Chat message stored in memory/database. Secrets must never enter content. */
@Serializable
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    /** Provider-neutral tool invocations carried by assistant messages. */
    val toolCalls: List<ToolCall> = emptyList(),
    /** Correlates a TOOL result with the provider tool invocation that produced it. */
    val toolCallId: String? = null,
    val toolName: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
enum class MessageRole { SYSTEM, USER, ASSISTANT, TOOL }

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val result: String? = null,
)

/** Capability states discovered at runtime, never hard-coded booleans. */
enum class CapabilityAvailabilityState {
    AVAILABLE,
    PERMISSION_REQUIRED,
    SERVICE_DISABLED,
    BACKEND_UNAVAILABLE,
    DEVICE_UNSUPPORTED,
    CONFIGURATION_REQUIRED,
    SECURITY_DENIED,
    DEGRADED,
}

data class CapabilityStatus(
    val id: CapabilityId,
    val state: CapabilityAvailabilityState,
    val detail: String? = null,
)

/** Identifies a platform capability. */
@JvmInline
value class CapabilityId(val value: String)

/** Risk level drives the Approval Engine policies. */
enum class RiskLevel {
    SAFE, READ, MODIFY, COMMUNICATION, FINANCIAL, SYSTEM_SENSITIVE
}

/** Approval policy decided by user/security config. */
enum class ApprovalPolicy { ALLOW, ASK_ONCE, ASK_EVERY_TIME, DENY }

/** Approval request presented to the Human Approval UI. */
data class ApprovalRequest(
    val id: String = java.util.UUID.randomUUID().toString(),
    val toolName: String,
    val action: String,
    val target: String,
    val argumentsSummary: String,
    val riskLevel: RiskLevel,
    val requestingAgent: String,
    val reason: String,
    val toolId: String = toolName,
    val runId: String = "",
    val agentScope: String = requestingAgent,
)

data class ApprovalDecision(
    val requestId: String,
    val decision: ApprovalOption,
)

enum class ApprovalOption { ALLOW_ONCE, ALLOW_FOR_TASK, ALWAYS_ALLOW, DENY, ASK }

/** Tool result envelope with typed error distinction. */
data class ToolResultEnvelope(
    val toolId: String,
    val success: Boolean,
    val data: String,
    val displayData: String? = null,
    val error: String? = null,
    val durationMs: Long,
    val isRetryable: Boolean = true,
    val errorCategory: ToolErrorCategory = ToolErrorCategory.GENERIC,
    val artifacts: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

/** Structured approval request payload presented to the human approval UI. */
data class ApprovalRequestDetails(
    val toolId: String,
    val toolName: String,
    val target: String,
    val arguments: Map<String, String>,
    val risk: RiskLevel,
    val reason: String,
    val runId: String,
    val agentId: String,
)

enum class ToolErrorCategory {
    GENERIC, RETRYABLE, PERMISSION, POLICY_DENIED, APPROVAL_REQUIRED, CAPABILITY_UNAVAILABLE, TIMEOUT, CANCELLED
}

/** Model capability metadata for adaptive Agent behaviour. */
data class ModelCapabilities(
    val chat: Boolean = true,
    val streaming: Boolean = false,
    val toolCalling: Boolean = false,
    val parallelToolCalling: Boolean = false,
    val vision: Boolean = false,
    val reasoning: Boolean = false,
    val jsonMode: Boolean = false,
    val structuredOutput: Boolean = false,
    val audioInput: Boolean = false,
    val audioOutput: Boolean = false,
    val embeddings: Boolean = false,
    val imageGeneration: Boolean = false,
    val responsesApi: Boolean = false,
    val contextWindow: Int = 4096,
    val maxOutputTokens: Int = 4096,
)

data class AiModel(
    val id: String,
    val name: String,
    val providerId: String,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val routing: ModelRoutingMetadata = ModelRoutingMetadata(),
)

enum class ModelDeployment { CLOUD, LOCAL }

enum class ModelSpeedTier { FAST, BALANCED, QUALITY }

data class ModelRoutingMetadata(
    val deployment: ModelDeployment = ModelDeployment.CLOUD,
    val speedTier: ModelSpeedTier = ModelSpeedTier.BALANCED,
    val codingOptimized: Boolean = false,
)

enum class DataSensitivity { STANDARD, SENSITIVE }

enum class WorkloadKind { AUTO, SIMPLE, GENERAL, CODING }

/** Explicit routing constraints. Sensitive/offline constraints never permit cloud fallback. */
data class RoutingHint(
    val sensitivity: DataSensitivity = DataSensitivity.STANDARD,
    val offline: Boolean = false,
    val requiresVision: Boolean = false,
    val workload: WorkloadKind = WorkloadKind.AUTO,
    val requiredContextTokens: Int = 0,
    val preferLocal: Boolean = false,
    val requestedProviderId: String? = null,
)

/** Generation request sent from the Agent Runtime to any provider. */
data class GenerationRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolDescriptor> = emptyList(),
    val modelId: String,
    val maxTokens: Int? = null,
    val temperature: Double = 0.7,
    val stream: Boolean = true,
    val routingHint: RoutingHint = RoutingHint(),
)

/** Typed tool descriptor used by Agent and Provider layers. */
data class ToolDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val inputSchema: String,
    val outputSchema: String,
    val riskLevel: RiskLevel,
    val requiredPermissions: Set<String> = emptySet(),
    val requiredCapabilities: Set<CapabilityId> = emptySet(),
    val timeoutMs: Long = 30_000L,
    val supportsCancellation: Boolean = true,
)

/** Execution timeline entry for observability (no chain-of-thought stored). */
data class RunTimelineEntry(
    val runId: String,
    val label: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val error: String? = null,
)

/** Run record for logs/observability feature. */
data class AgentRun(
    val runId: String,
    val agentId: String,
    val provider: String,
    val model: String,
    val startedAt: Long,
    val parentRunId: String? = null,
    val memoryNamespace: String? = null,
    var completedAt: Long? = null,
    var promptTokens: Int = 0,
    var completionTokens: Int = 0,
    var toolCalls: Int = 0,
    var approvals: Int = 0,
    var errors: Int = 0,
    var estimatedCost: Double = 0.0,
    var status: String = "running",
)
