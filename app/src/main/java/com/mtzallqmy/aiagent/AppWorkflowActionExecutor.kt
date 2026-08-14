package com.mtzallqmy.aiagent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mtzallqmy.aiagent.agent.AgentRuntime
import com.mtzallqmy.aiagent.agent.ProviderRegistry
import com.mtzallqmy.aiagent.model.AgentState
import com.mtzallqmy.aiagent.model.ApprovalOption
import com.mtzallqmy.aiagent.model.ApprovalRequest
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.tools.ApprovalEngine
import com.mtzallqmy.aiagent.tools.ToolContext
import com.mtzallqmy.aiagent.tools.ToolRuntime
import com.mtzallqmy.aiagent.tools.TypedToolRegistry
import com.mtzallqmy.aiagent.workflow.AgentNode
import com.mtzallqmy.aiagent.workflow.ApprovalNode
import com.mtzallqmy.aiagent.workflow.NotificationNode
import com.mtzallqmy.aiagent.workflow.ToolNode
import com.mtzallqmy.aiagent.workflow.WorkflowActionExecutor
import com.mtzallqmy.aiagent.workflow.WorkflowExecutionContext
import com.mtzallqmy.aiagent.workflow.WorkflowNode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Production adapters for external workflow node effects. */
class AppWorkflowActionExecutor(
    context: Context,
    private val providers: ProviderRegistry,
    private val tools: TypedToolRegistry,
    private val toolRuntime: ToolRuntime,
    private val approvalEngine: ApprovalEngine,
) : WorkflowActionExecutor {
    private val appContext = context.applicationContext

    override suspend fun execute(node: WorkflowNode, context: WorkflowExecutionContext): JsonObject = when (node) {
        is AgentNode -> executeAgent(node)
        is ToolNode -> executeTool(node, context)
        is ApprovalNode -> executeApproval(node, context)
        is NotificationNode -> executeNotification(node, context)
        else -> error("Node ${node.id} is internal and must not enter WorkflowActionExecutor")
    }

    private suspend fun executeAgent(node: AgentNode): JsonObject = coroutineScope {
        val provider = providers.select(node.providerId ?: "smart-router")
        val allowedTools = node.toolIds.map { id ->
            tools.get(id) ?: throw IllegalArgumentException("Workflow Agent node references unknown tool: $id")
        }
        val runtime = AgentRuntime(provider, toolRuntime)
        val output = StringBuilder()
        var finalOutput: String? = null
        var failure: String? = null
        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.events.collect { event ->
                when (event) {
                    is GenerationEvent.TextDelta -> output.append(event.text)
                    is GenerationEvent.GenerationCompleted -> {
                        if (event.finalText.isNotBlank()) finalOutput = event.finalText
                    }
                    is GenerationEvent.GenerationFailed -> failure = event.error.message
                    else -> Unit
                }
            }
        }
        check(
            runtime.runTask(
                task = node.prompt,
                modelId = node.modelId.orEmpty(),
                agentId = node.agentId,
                tools = allowedTools,
            ) != null,
        ) { "Workflow Agent runtime refused to start" }
        val finalState = runtime.state.filter { it in TERMINAL_AGENT_STATES }.first()
        eventCollector.cancelAndJoin()
        if (finalState != AgentState.COMPLETED) {
            error(failure ?: "Workflow agent ended in $finalState")
        }
        JsonObject(mapOf("text" to JsonPrimitive(output.toString().ifBlank { finalOutput.orEmpty() })))
    }

    private suspend fun executeTool(node: ToolNode, context: WorkflowExecutionContext): JsonObject {
        val tool = tools.get(node.toolId) ?: throw IllegalArgumentException("Unknown workflow tool: ${node.toolId}")
        val result = toolRuntime.execute(
            tool = tool,
            input = node.input,
            context = ToolContext(context.runId, context.workflowId, context.idempotencyKey),
            runId = context.runId,
            agentId = "workflow:${context.workflowId}",
            maxRetries = 0,
        )
        if (!result.success) error(result.error ?: "Workflow tool failed")
        return JsonObject(
            mapOf(
                "data" to JsonPrimitive(result.data),
                "durationMillis" to JsonPrimitive(result.durationMs),
                "idempotencyKey" to JsonPrimitive(context.idempotencyKey),
            ),
        )
    }

    private suspend fun executeApproval(node: ApprovalNode, context: WorkflowExecutionContext): JsonObject {
        val risk = runCatching { RiskLevel.valueOf(node.risk) }
            .getOrElse { throw IllegalArgumentException("Unknown workflow approval risk: ${node.risk}") }
        val request = ApprovalRequest(
            id = context.idempotencyKey,
            toolName = "Workflow approval",
            toolId = "workflow.approval",
            action = "approve",
            target = "${context.workflowId}:${node.id}",
            argumentsSummary = node.summary.take(200),
            riskLevel = risk,
            requestingAgent = "workflow:${context.workflowId}",
            agentScope = "workflow:${context.workflowId}",
            runId = context.runId,
            reason = node.title,
        )
        val immediate = approvalEngine.decide(request)
        val decision = if (immediate.decision == ApprovalOption.ASK) {
            approvalEngine.requestApproval(request)
        } else immediate
        if (decision.decision == ApprovalOption.DENY || decision.decision == ApprovalOption.ASK) {
            error("Workflow approval denied")
        }
        return JsonObject(mapOf("decision" to JsonPrimitive(decision.decision.name)))
    }

    private fun executeNotification(node: NotificationNode, context: WorkflowExecutionContext): JsonObject {
        require(node.channel == LOCAL_CHANNEL) { "Unsupported notification channel: ${node.channel}" }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) error("Notification permission is not granted")

        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(LOCAL_CHANNEL, "Aegis workflows", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = NotificationCompat.Builder(appContext, LOCAL_CHANNEL)
            .setSmallIcon(com.mtzallqmy.aiagent.R.drawable.ic_launcher_foreground)
            .setContentTitle("Aegis")
            .setContentText(node.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(node.message))
            .setAutoCancel(true)
            .build()
        // A stable ID updates the same notification after crash recovery.
        manager.notify(context.idempotencyKey.hashCode(), notification)
        return JsonObject(mapOf("posted" to JsonPrimitive(true)))
    }

    private companion object {
        const val LOCAL_CHANNEL = "local"
        val TERMINAL_AGENT_STATES = setOf(AgentState.COMPLETED, AgentState.FAILED, AgentState.CANCELLED)
    }
}
