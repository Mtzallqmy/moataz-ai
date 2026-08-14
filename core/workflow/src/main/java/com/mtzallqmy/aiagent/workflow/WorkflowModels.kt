package com.mtzallqmy.aiagent.workflow

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

@Serializable
data class WorkflowDefinition(
    val id: String,
    val version: Int,
    val name: String,
    val entryNodeId: String,
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
)

@Serializable
data class WorkflowEdge(
    val from: String,
    val to: String,
    val label: String = "next",
)

@Serializable
data class RetryPolicy(
    val maxAttempts: Int = 1,
    val initialDelayMillis: Long = 250,
    val multiplier: Double = 2.0,
    val maxDelayMillis: Long = 30_000,
) {
    init {
        require(maxAttempts in 1..20)
        require(initialDelayMillis >= 0)
        require(multiplier in 1.0..10.0)
        require(maxDelayMillis >= initialDelayMillis)
    }
}

@Serializable
sealed interface WorkflowNode {
    val id: String
    val timeoutMillis: Long
    val retry: RetryPolicy
}

@Serializable @SerialName("trigger")
data class TriggerNode(
    override val id: String,
    override val timeoutMillis: Long = 5_000,
    override val retry: RetryPolicy = RetryPolicy(),
) : WorkflowNode

@Serializable @SerialName("agent")
data class AgentNode(
    override val id: String,
    val agentId: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val prompt: String,
    val toolIds: Set<String> = emptySet(),
    override val timeoutMillis: Long = 10 * 60_000,
    override val retry: RetryPolicy = RetryPolicy(maxAttempts = 2),
) : WorkflowNode

@Serializable @SerialName("tool")
data class ToolNode(
    override val id: String,
    val toolId: String,
    val input: JsonObject = JsonObject(emptyMap()),
    override val timeoutMillis: Long = 60_000,
    override val retry: RetryPolicy = RetryPolicy(maxAttempts = 2),
) : WorkflowNode

@Serializable @SerialName("condition")
data class ConditionNode(
    override val id: String,
    val predicate: WorkflowPredicate,
    override val timeoutMillis: Long = 5_000,
    override val retry: RetryPolicy = RetryPolicy(),
) : WorkflowNode

@Serializable @SerialName("loop")
data class LoopNode(
    override val id: String,
    val predicate: WorkflowPredicate,
    val maxIterations: Int,
    override val timeoutMillis: Long = 5_000,
    override val retry: RetryPolicy = RetryPolicy(),
) : WorkflowNode

@Serializable @SerialName("parallel")
data class ParallelNode(
    override val id: String,
    override val timeoutMillis: Long = 5_000,
    override val retry: RetryPolicy = RetryPolicy(),
) : WorkflowNode

@Serializable @SerialName("delay")
data class DelayNode(
    override val id: String,
    val delayMillis: Long,
    override val timeoutMillis: Long = delayMillis + 5_000,
    override val retry: RetryPolicy = RetryPolicy(),
) : WorkflowNode

@Serializable @SerialName("transform")
data class TransformNode(
    override val id: String,
    val assignments: Map<String, WorkflowValue>,
    override val timeoutMillis: Long = 5_000,
    override val retry: RetryPolicy = RetryPolicy(),
) : WorkflowNode

@Serializable @SerialName("approval")
data class ApprovalNode(
    override val id: String,
    val title: String,
    val summary: String,
    val risk: String,
    override val timeoutMillis: Long = 24 * 60 * 60_000L,
    override val retry: RetryPolicy = RetryPolicy(),
) : WorkflowNode

@Serializable @SerialName("notification")
data class NotificationNode(
    override val id: String,
    val channel: String,
    val message: String,
    override val timeoutMillis: Long = 60_000,
    override val retry: RetryPolicy = RetryPolicy(maxAttempts = 3),
) : WorkflowNode

@Serializable @SerialName("output")
data class OutputNode(
    override val id: String,
    val value: WorkflowValue,
    override val timeoutMillis: Long = 5_000,
    override val retry: RetryPolicy = RetryPolicy(),
) : WorkflowNode

@Serializable
sealed interface WorkflowValue {
    @Serializable @SerialName("literal")
    data class Literal(val value: JsonElement = JsonNull) : WorkflowValue

    @Serializable @SerialName("run_input")
    data class RunInput(val path: String? = null) : WorkflowValue

    @Serializable @SerialName("node_output")
    data class NodeOutput(val nodeId: String, val path: String? = null) : WorkflowValue
}

@Serializable
data class WorkflowPredicate(
    val left: WorkflowValue,
    val operator: PredicateOperator,
    val right: WorkflowValue? = null,
)

@Serializable
enum class PredicateOperator { EXISTS, NOT_EXISTS, EQUALS, NOT_EQUALS, TRUE, FALSE }

@Serializable
enum class WorkflowRunStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

@Serializable
data class ParallelFrame(
    val groupId: String,
    val parallelNodeId: String,
    val branchIndex: Int,
)

@Serializable
data class ExecutionToken(
    val id: String,
    val nodeId: String,
    val loopIterations: Map<String, Int> = emptyMap(),
    val parallelFrames: List<ParallelFrame> = emptyList(),
)

@Serializable
data class ParallelGroupState(
    val id: String,
    val nodeId: String,
    val expectedBranches: Int,
    val arrivedBranches: Set<Int> = emptySet(),
    val parentFrames: List<ParallelFrame> = emptyList(),
    val loopIterations: Map<String, Int> = emptyMap(),
)

@Serializable
data class WorkflowTimelineEvent(
    val nodeId: String?,
    val label: String,
    val timestampMillis: Long,
    val attempt: Int? = null,
    val error: String? = null,
)

@Serializable
data class WorkflowRun(
    val id: String,
    val workflowId: String,
    val workflowVersion: Int,
    val status: WorkflowRunStatus,
    val input: JsonObject,
    val pendingTokens: List<ExecutionToken>,
    val nodeOutputs: Map<String, JsonObject> = emptyMap(),
    val parallelGroups: Map<String, ParallelGroupState> = emptyMap(),
    val timeline: List<WorkflowTimelineEvent> = emptyList(),
    val output: JsonElement? = null,
    val error: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
