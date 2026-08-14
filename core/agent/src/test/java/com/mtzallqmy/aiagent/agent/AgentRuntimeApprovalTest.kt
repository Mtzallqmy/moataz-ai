package com.mtzallqmy.aiagent.agent

import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.model.AgentState
import com.mtzallqmy.aiagent.model.AiModel
import com.mtzallqmy.aiagent.model.ApprovalOption
import com.mtzallqmy.aiagent.model.ApprovalPolicy
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import com.mtzallqmy.aiagent.model.MessageRole
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import com.mtzallqmy.aiagent.providers.AiProvider
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.ApprovalEngine
import com.mtzallqmy.aiagent.tools.RegisteredTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import com.mtzallqmy.aiagent.tools.ToolRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AgentRuntimeApprovalTest {

    @Test
    fun `one agent tool call produces one approval request and one execution`() = runBlocking {
        val approvalEngine = ApprovalEngine { ApprovalPolicy.ASK_EVERY_TIME }
        val tool = CountingTool()
        val provider = SingleToolCallProvider(tool.descriptor.id)
        val runtime = AgentRuntime(
            provider = provider,
            toolRuntime = ToolRuntime(CapabilityRegistry(), approvalEngine),
        )

        runtime.runTask("use the tool", "test-model", tools = listOf(registered(tool)))

        val request = withTimeout(2_000) { approvalEngine.requests.receive() }
        assertEquals(AgentState.WAITING_FOR_APPROVAL, runtime.state.value)
        assertNull(approvalEngine.requests.tryReceive().getOrNull())
        approvalEngine.respond(request.id, ApprovalOption.ALLOW_ONCE)

        withTimeout(2_000) { runtime.state.first { it == AgentState.COMPLETED } }
        assertEquals(1, tool.executionCount)
        assertNull(approvalEngine.requests.tryReceive().getOrNull())
        assertTrue(runtime.run.value?.approvals == 1)

        val followUp = provider.requests.getOrNull(1) ?: error("Provider follow-up request was not captured")
        val assistantToolCall = followUp.messages.firstOrNull {
            it.role == MessageRole.ASSISTANT && it.toolCalls.any { call -> call.id == "call-1" }
        }
        val toolResult = followUp.messages.firstOrNull {
            it.role == MessageRole.TOOL && it.toolCallId == "call-1" && it.toolName == tool.descriptor.id
        }
        assertTrue("Assistant tool-call context must survive into the next provider turn", assistantToolCall != null)
        assertTrue("Tool result must be correlated with the original provider call id", toolResult != null)
    }

    @Test
    fun `agent mirrors waiting state from tool runtime and cancellation stops pending call`() = runBlocking {
        val approvalEngine = ApprovalEngine { ApprovalPolicy.ASK_EVERY_TIME }
        val tool = CountingTool()
        val runtime = AgentRuntime(
            provider = SingleToolCallProvider(tool.descriptor.id),
            toolRuntime = ToolRuntime(CapabilityRegistry(), approvalEngine),
        )

        runtime.runTask("use the tool", "test-model", tools = listOf(registered(tool)))

        withTimeout(2_000) { runtime.state.first { it == AgentState.WAITING_FOR_APPROVAL } }
        approvalEngine.requests.receive()
        assertEquals(0, tool.executionCount)
        assertNull(approvalEngine.requests.tryReceive().getOrNull())

        runtime.cancel()
        withTimeout(2_000) {
            while (approvalEngine.pendingCount != 0) delay(10)
        }
        assertEquals(AgentState.CANCELLED, runtime.state.value)
        assertEquals(0, tool.executionCount)
    }

    private class SingleToolCallProvider(private val toolId: String) : AiProvider {
        private val generationCount = AtomicInteger(0)
        val requests = java.util.Collections.synchronizedList(mutableListOf<GenerationRequest>())
        override val providerId = "test"
        override val name = "Test"
        override suspend fun listModels(): Result<List<AiModel>> = Result.success(emptyList())
        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)

        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
            requests += request
            if (generationCount.getAndIncrement() == 0) {
                emit(GenerationEvent.ToolCallStarted("call-1", toolId))
                emit(GenerationEvent.ToolCallArgumentsDelta("call-1", "{}"))
                emit(GenerationEvent.GenerationCompleted(""))
            } else {
                emit(GenerationEvent.GenerationCompleted("done"))
            }
        }
    }

    private class CountingTool : AgentTool<kotlinx.serialization.json.JsonObject, Any> {
        var executionCount = 0
        override val descriptor = ToolDescriptor(
            id = "agent-counting-tool",
            displayName = "Agent counting tool",
            description = "Counts executions",
            inputSchema = """{"type":"object"}""",
            outputSchema = """{"type":"object"}""",
            riskLevel = RiskLevel.MODIFY,
        )

        override suspend fun availability(context: ToolContext): ToolAvailability = ToolAvailability.Available

        override suspend fun execute(input: kotlinx.serialization.json.JsonObject, context: ToolContext): Any {
            executionCount += 1
            return "ok"
        }
    }

    private fun registered(tool: CountingTool) =
        RegisteredTool.typed(tool, kotlinx.serialization.json.JsonObject.serializer())
}
