package com.mtzallqmy.aiagent.agent

import com.mtzallqmy.aiagent.capabilities.Capability
import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.model.AiModel
import com.mtzallqmy.aiagent.model.ApprovalPolicy
import com.mtzallqmy.aiagent.model.CapabilityAvailabilityState
import com.mtzallqmy.aiagent.model.CapabilityId
import com.mtzallqmy.aiagent.model.GenerationEvent
import com.mtzallqmy.aiagent.model.GenerationRequest
import com.mtzallqmy.aiagent.model.MessageRole
import com.mtzallqmy.aiagent.model.ModelCapabilities
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import com.mtzallqmy.aiagent.providers.AiProvider
import com.mtzallqmy.aiagent.tools.AgentTool
import com.mtzallqmy.aiagent.tools.ApprovalEngine
import com.mtzallqmy.aiagent.tools.RegisteredTool
import com.mtzallqmy.aiagent.tools.ToolAvailability
import com.mtzallqmy.aiagent.tools.ToolContext
import com.mtzallqmy.aiagent.tools.ToolRuntime
import com.mtzallqmy.aiagent.tools.TypedToolRegistry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRunnerTest {
    @Test
    fun capabilityScopeCannotBeInheritedFromMainRuntime() = runBlocking {
        val fixture = fixture(toolCapability = CapabilityId("device.control"))
        fixture.capabilities.register(availableCapability(CapabilityId("device.control")))
        val result = fixture.runner.start(
            spec(toolAllowlist = setOf(TOOL_ID), capabilityScope = emptySet()),
            "Perform a delegated task",
        ).await()

        assertEquals(SubAgentStatus.COMPLETED, result.status)
        assertEquals(0, fixture.executions.get())
        assertTrue(result.memoryNamespace.startsWith("subagent:parent-run:browser-1:"))
        assertTrue(result.memoryPersisted)
        fixture.close()
    }

    @Test
    fun toolAllowlistDoesNotExpandForProviderRequestedTool() = runBlocking {
        val fixture = fixture()
        val result = fixture.runner.start(
            spec(toolAllowlist = emptySet()),
            "Perform a delegated task",
        ).await()

        assertEquals(SubAgentStatus.COMPLETED, result.status)
        assertEquals(0, fixture.executions.get())
        fixture.close()
    }

    @Test
    fun zeroToolBudgetPreventsExecution() = runBlocking {
        val fixture = fixture()
        val result = fixture.runner.start(
            spec(toolAllowlist = setOf(TOOL_ID), toolCallBudget = 0),
            "Perform a delegated task",
        ).await()

        assertEquals(SubAgentStatus.COMPLETED, result.status)
        assertEquals(0, fixture.executions.get())
        fixture.close()
    }

    @Test
    fun cancellationCompletesParentHandoff() = runBlocking {
        val fixture = fixture(provider = BlockingProvider())
        val handle = fixture.runner.start(spec(toolAllowlist = emptySet()), "Wait")
        assertTrue(handle.cancel())
        val result = withTimeout(5_000) { handle.await() }

        assertEquals(SubAgentStatus.CANCELLED, result.status)
        assertFalse(result.memoryPersisted)
        fixture.close()
    }

    @Test
    fun timeoutIsReportedSeparatelyFromCancellation() = runBlocking {
        val fixture = fixture(provider = BlockingProvider())
        val result = withTimeout(5_000) {
            fixture.runner.start(
                spec(toolAllowlist = emptySet(), timeoutMs = 1_000),
                "Wait until the delegated timeout",
            ).await()
        }

        assertEquals(SubAgentStatus.TIMED_OUT, result.status)
        fixture.close()
    }

    @Test
    fun tokenBudgetIsEnforcedAndReported() = runBlocking {
        val fixture = fixture(provider = UsageProvider())
        val result = fixture.runner.start(
            spec(toolAllowlist = emptySet(), tokenBudget = 100),
            "Use the configured token budget",
        ).await()

        assertEquals(SubAgentStatus.BUDGET_EXCEEDED, result.status)
        fixture.close()
    }

    private fun fixture(
        toolCapability: CapabilityId? = null,
        provider: AiProvider = ToolCallingProvider(),
    ): Fixture {
        val providerRegistry = ProviderRegistry().apply { register(provider) }
        val capabilities = CapabilityRegistry()
        val approval = ApprovalEngine(policyProvider = { ApprovalPolicy.ALLOW })
        val toolRuntime = ToolRuntime(capabilities, approval)
        val executions = AtomicInteger()
        val descriptor = ToolDescriptor(
            id = TOOL_ID,
            displayName = TOOL_ID,
            description = "Test delegated execution",
            inputSchema = """{"type":"object","additionalProperties":false}""",
            outputSchema = """{"type":"string"}""",
            riskLevel = RiskLevel.SAFE,
            requiredCapabilities = toolCapability?.let(::setOf).orEmpty(),
        )
        val tool = object : AgentTool<JsonObject, String> {
            override val descriptor = descriptor
            override suspend fun availability(context: ToolContext) = ToolAvailability.Available
            override suspend fun execute(input: JsonObject, context: ToolContext): String {
                executions.incrementAndGet()
                return "executed"
            }
        }
        val tools = TypedToolRegistry().apply {
            register(RegisteredTool.typed(tool, JsonObject.serializer()))
        }
        val memory = RecordingMemory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return Fixture(
            runner = SubAgentRunner(providerRegistry, tools, toolRuntime, memory, scope),
            capabilities = capabilities,
            executions = executions,
            scope = scope,
        )
    }

    private fun spec(
        toolAllowlist: Set<String>,
        capabilityScope: Set<CapabilityId> = emptySet(),
        toolCallBudget: Int = 4,
        tokenBudget: Int = 1_000,
        timeoutMs: Long = 10_000,
    ) = SubAgentSpec(
        agentId = "browser-1",
        role = SubAgentRole.BROWSER,
        systemPrompt = "Perform browser research and report verified observations.",
        providerId = PROVIDER_ID,
        modelId = MODEL_ID,
        toolAllowlist = toolAllowlist,
        capabilityScope = capabilityScope,
        tokenBudget = tokenBudget,
        toolCallBudget = toolCallBudget,
        timeoutMs = timeoutMs,
        memoryNamespace = "working",
        riskPolicy = SubAgentRiskPolicy.READ_ONLY,
        parentRunId = "parent-run",
    )

    private fun availableCapability(id: CapabilityId) = object : Capability {
        override val id = id
        override suspend fun availability() = CapabilityAvailabilityState.AVAILABLE
    }

    private data class Fixture(
        val runner: SubAgentRunner,
        val capabilities: CapabilityRegistry,
        val executions: AtomicInteger,
        val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()
    }

    private class RecordingMemory : SubAgentMemoryGateway {
        override suspend fun load(namespace: String, limit: Int) = emptyList<SubAgentMemoryEntry>()
        override suspend fun storeResult(namespace: String, runId: String, value: String) = Unit
    }

    private class ToolCallingProvider : AiProvider {
        override val providerId = PROVIDER_ID
        override val name = "Deterministic test provider"
        override suspend fun listModels() = Result.success(listOf(model()))
        override suspend fun testConnection() = Result.success(Unit)
        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
            if (request.messages.any { it.role == MessageRole.TOOL }) {
                emit(GenerationEvent.TextDelta("done"))
                emit(GenerationEvent.GenerationCompleted("done"))
            } else {
                emit(GenerationEvent.ToolCallStarted("call-1", TOOL_ID))
                emit(GenerationEvent.ToolCallArgumentsDelta("call-1", "{}"))
                emit(GenerationEvent.ToolCallCompleted("call-1", ""))
                emit(GenerationEvent.GenerationCompleted(""))
            }
        }
    }

    private class BlockingProvider : AiProvider {
        override val providerId = PROVIDER_ID
        override val name = "Blocking test provider"
        override suspend fun listModels() = Result.success(listOf(model()))
        override suspend fun testConnection() = Result.success(Unit)
        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow { awaitCancellation() }
    }

    private class UsageProvider : AiProvider {
        override val providerId = PROVIDER_ID
        override val name = "Usage test provider"
        override suspend fun listModels() = Result.success(listOf(model()))
        override suspend fun testConnection() = Result.success(Unit)
        override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
            emit(GenerationEvent.Usage(promptTokens = 100, completionTokens = 1))
            emit(GenerationEvent.GenerationCompleted("budgeted"))
        }
    }

    private companion object {
        const val PROVIDER_ID = "test-provider"
        const val MODEL_ID = "test-model"
        const val TOOL_ID = "test.tool"

        fun model() = AiModel(
            id = MODEL_ID,
            name = MODEL_ID,
            providerId = PROVIDER_ID,
            capabilities = ModelCapabilities(streaming = true, toolCalling = true),
        )
    }
}
