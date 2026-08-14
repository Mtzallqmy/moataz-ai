package com.mtzallqmy.aiagent.tools

import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.common.AgentException
import com.mtzallqmy.aiagent.common.SecretSanitizer
import com.mtzallqmy.aiagent.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException

/** Typed tool contract: primitive platform capability. */
interface AgentTool<I : Any, O : Any> {
    val descriptor: ToolDescriptor
    suspend fun availability(context: ToolContext): ToolAvailability
    suspend fun execute(input: I, context: ToolContext): O
}

data class ToolContext(
    val runId: String,
    val workspaceId: String,
    /** Stable key for crash-retry deduplication when the calling workflow provides one. */
    val idempotencyKey: String? = null,
    /** Null means the owning main runtime did not apply an additional capability scope. */
    val capabilityScope: Set<CapabilityId>? = null,
    /** Null means the owning main runtime did not apply an additional risk scope. */
    val allowedRiskLevels: Set<RiskLevel>? = null,
    val memoryNamespace: String? = null,
    val parentRunId: String? = null,
)

sealed class ToolAvailability {
    data object Available : ToolAvailability()
    data class Unavailable(val reason: String) : ToolAvailability()
}

/** Observable execution states owned by [ToolRuntime]. */
enum class ToolRuntimeState {
    CHECKING_POLICY,
    WAITING_FOR_APPROVAL,
    CHECKING_CAPABILITIES,
    EXECUTING,
}

/**
 * Real Tool Runtime:
 * - validates tool input against the tool's JSON schema (no raw-string pass-through)
 * - checks capability + availability before anything executes
 * - routes sensitive actions through the ApprovalEngine and SUSPENDS until a
 *   human decides (never executes before the decision)
 * - enforces timeouts, cancellation, and per-run tool-call budgets
 * - retries retryable errors with exponential backoff
 * - never fakes execution
 */
class ToolRuntime(
    private val capabilityRegistry: CapabilityRegistry,
    private val approvalEngine: ApprovalEngine,
) {
    private val budgetLock = Any()
    private val toolCallCounts = mutableMapOf<String, Int>()

    /** Drops run-scoped approval grants when the owning agent run terminates. */
    fun clearApprovalScope(runId: String) {
        approvalEngine.clearRun(runId)
        synchronized(budgetLock) { toolCallCounts.remove(runId) }
    }

    suspend fun registerAndList(tools: List<RegisteredTool>) = tools

    suspend fun execute(
        tool: RegisteredTool,
        input: Any,
        context: ToolContext,
        runId: String,
        agentId: String = "main",
        maxToolCallsPerRun: Int = 50,
        maxRetries: Int = 1,
        onStateChange: (ToolRuntimeState) -> Unit = {},
    ): ToolResultEnvelope {
        require(maxToolCallsPerRun > 0) { "maxToolCallsPerRun must be positive" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }

        // 0. Schema validation: LLM arguments -> JSON parser -> schema validator -> typed input
        val typedInput = when (input) {
            is kotlinx.serialization.json.JsonObject -> input
            is String -> {
                when (val result = ToolSchemaValidator.validate(input, tool.descriptor.inputSchema)) {
                    is ToolSchemaValidator.ValidationResult.Valid -> result.input
                    is ToolSchemaValidator.ValidationResult.Invalid -> {
                        return failure(tool, "Invalid tool input: ${result.errors.joinToString("; ")}",
                            ToolErrorCategory.GENERIC, isRetryable = false)
                    }
                }
            }
            else -> {
                // Platform-initiated calls may already be typed objects; only LLM args are JSON strings.
                return failure(tool, "Tool input must be a JSON object",
                    ToolErrorCategory.GENERIC, isRetryable = false)
            }
        }

        // Kotlin Serialization is the mandatory boundary between validated JSON and execution.
        val preparedCall = try {
            tool.prepare(typedInput)
        } catch (e: SerializationException) {
            return failure(
                tool,
                "Invalid typed tool input: ${e.message?.take(200)}",
                ToolErrorCategory.GENERIC,
                isRetryable = false,
            )
        } catch (e: IllegalArgumentException) {
            return failure(
                tool,
                "Invalid typed tool input: ${e.message?.take(200)}",
                ToolErrorCategory.GENERIC,
                isRetryable = false,
            )
        }

        // 1. Budget
        val count = synchronized(budgetLock) {
            val next = toolCallCounts.getOrDefault(runId, 0) + 1
            if (next <= maxToolCallsPerRun) toolCallCounts[runId] = next
            next
        }
        if (count > maxToolCallsPerRun) {
            return failure(tool, "Tool-call budget exceeded ($maxToolCallsPerRun)",
                ToolErrorCategory.GENERIC, isRetryable = false)
        }
        val start = System.currentTimeMillis()

        // 2. Runtime scope policy. A delegated agent can only reduce authority.
        onStateChange(ToolRuntimeState.CHECKING_POLICY)
        if (context.allowedRiskLevels?.contains(tool.descriptor.riskLevel) == false) {
            return failure(
                tool,
                "Risk ${tool.descriptor.riskLevel} is outside the delegated agent policy",
                ToolErrorCategory.POLICY_DENIED,
                durationMs = System.currentTimeMillis() - start,
                isRetryable = false,
            )
        }
        val capabilityScope = context.capabilityScope
        if (capabilityScope != null && !capabilityScope.containsAll(tool.descriptor.requiredCapabilities)) {
            return failure(
                tool,
                "Tool requires capabilities outside the delegated agent scope",
                ToolErrorCategory.POLICY_DENIED,
                durationMs = System.currentTimeMillis() - start,
                isRetryable = false,
            )
        }

        // 3. Approval. This is the only approval path for every tool call.
        val approvalRequest = ApprovalRequest(
            toolName = tool.descriptor.displayName,
            toolId = tool.descriptor.id,
            action = "execute",
            target = tool.descriptor.id,
            argumentsSummary = SecretSanitizer.sanitize(typedInput.toString()).take(200),
            riskLevel = tool.descriptor.riskLevel,
            requestingAgent = agentId,
            agentScope = agentId,
            runId = runId,
            reason = "Requested during run $runId",
        )
        val immediate = approvalEngine.decide(approvalRequest)
        val decision = when (immediate.decision) {
            ApprovalOption.ASK -> {
                onStateChange(ToolRuntimeState.WAITING_FOR_APPROVAL)
                approvalEngine.requestApproval(approvalRequest)
            }
            else -> immediate
        }
        if (decision.decision == ApprovalOption.DENY || decision.decision == ApprovalOption.ASK) {
            return failure(tool, "Approval denied", ToolErrorCategory.APPROVAL_REQUIRED,
                durationMs = System.currentTimeMillis() - start, isRetryable = false)
        }

        // 4. Availability via Capability Registry
        onStateChange(ToolRuntimeState.CHECKING_CAPABILITIES)
        val availability = tool.availability(context)
        if (availability !is ToolAvailability.Available) {
            return failure(tool, (availability as ToolAvailability.Unavailable).reason,
                ToolErrorCategory.CAPABILITY_UNAVAILABLE, durationMs = System.currentTimeMillis() - start)
        }

        // 5. Capability gate
        for (cap in tool.descriptor.requiredCapabilities) {
            val capStatus = capabilityRegistry.status(cap)
            if (capStatus.state != CapabilityAvailabilityState.AVAILABLE &&
                capStatus.state != CapabilityAvailabilityState.DEGRADED
            ) {
                return failure(tool, "Capability ${cap.value} is ${capStatus.state}",
                    ToolErrorCategory.CAPABILITY_UNAVAILABLE, durationMs = System.currentTimeMillis() - start)
            }
        }

        // 6. Timed execution with cancellation + retries
        onStateChange(ToolRuntimeState.EXECUTING)
        val automaticRetryAllowed = tool.descriptor.riskLevel == RiskLevel.SAFE ||
            tool.descriptor.riskLevel == RiskLevel.READ
        var attempt = 0
        while (true) {
            attempt++
            val result = try {
                withTimeout(tool.descriptor.timeoutMs) {
                    val output = preparedCall.execute(context)
                    ToolResultEnvelope(
                        toolId = tool.descriptor.id, success = true, data = output.toString(),
                        durationMs = System.currentTimeMillis() - start,
                        metadata = mapOf("attempts" to attempt.toString(), "toolVersion" to tool.descriptor.id),
                    )
                }
            } catch (e: TimeoutCancellationException) {
                // A timed-out modifying action has an unknown completion state. Never
                // replay it automatically; that could duplicate a side effect.
                failure(
                    tool,
                    "Timeout",
                    ToolErrorCategory.TIMEOUT,
                    durationMs = tool.descriptor.timeoutMs,
                    isRetryable = automaticRetryAllowed,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentException.ToolCancelledError) {
                failure(tool, e.message ?: "Cancelled", ToolErrorCategory.CANCELLED,
                    durationMs = System.currentTimeMillis() - start, isRetryable = false)
            } catch (e: Throwable) {
                failure(
                    tool,
                    e.message ?: "Unknown error",
                    ToolErrorCategory.GENERIC,
                    durationMs = System.currentTimeMillis() - start,
                    isRetryable = automaticRetryAllowed,
                )
            }

            if (result.success || !result.isRetryable || attempt >= maxRetries + 1) return result
            // Exponential backoff: 500ms, 1s, ...
            delay(500L * (1 shl (attempt - 1)))
        }
    }

    private fun failure(
        tool: RegisteredTool, error: String, category: ToolErrorCategory,
        durationMs: Long = 0L, isRetryable: Boolean = true,
    ): ToolResultEnvelope = ToolResultEnvelope(
        toolId = tool.descriptor.id,
        success = false,
        data = "",
        error = SecretSanitizer.sanitize(error),
        durationMs = durationMs,
        isRetryable = isRetryable,
        errorCategory = category,
    )
}
