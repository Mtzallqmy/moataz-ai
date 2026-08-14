package com.mtzallqmy.aiagent.tools

import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.model.ApprovalPolicy
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import com.mtzallqmy.aiagent.model.ToolErrorCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRuntimeExecutionTest {

    @Test
    fun `tool timeout is returned as typed timeout instead of cancelling the agent coroutine`() = runTest {
        val tool = object : AgentTool<JsonObject, String> {
            override val descriptor = descriptor(timeoutMs = 50L)
            override suspend fun availability(context: ToolContext) = ToolAvailability.Available
            override suspend fun execute(input: JsonObject, context: ToolContext): String {
                delay(5_000L)
                return "late"
            }
        }

        val result = runtime().execute(registered(tool), "{}", context, "timeout-run", maxRetries = 0)

        assertFalse(result.success)
        assertEquals(ToolErrorCategory.TIMEOUT, result.errorCategory)
    }

    @Test
    fun `maxRetries one performs at most two total attempts`() = runTest {
        var executions = 0
        val tool = object : AgentTool<JsonObject, String> {
            override val descriptor = descriptor()
            override suspend fun availability(context: ToolContext) = ToolAvailability.Available
            override suspend fun execute(input: JsonObject, context: ToolContext): String {
                executions += 1
                if (executions == 1) error("retry me")
                return "ok"
            }
        }

        val result = runtime().execute(registered(tool), "{}", context, "retry-run", maxRetries = 1)

        assertTrue(result.success)
        assertEquals(2, executions)
        assertEquals("2", result.metadata["attempts"])
    }

    @Test
    fun `maxRetries zero never performs an implicit second execution`() = runTest {
        var executions = 0
        val tool = object : AgentTool<JsonObject, String> {
            override val descriptor = descriptor()
            override suspend fun availability(context: ToolContext) = ToolAvailability.Available
            override suspend fun execute(input: JsonObject, context: ToolContext): String {
                executions += 1
                error("fail")
            }
        }

        val result = runtime().execute(registered(tool), "{}", context, "no-retry-run", maxRetries = 0)

        assertFalse(result.success)
        assertEquals(1, executions)
    }


    @Test
    fun `modify tools are never automatically replayed after ambiguous failure`() = runTest {
        var executions = 0
        val tool = object : AgentTool<JsonObject, String> {
            override val descriptor = descriptor(riskLevel = RiskLevel.MODIFY)
            override suspend fun availability(context: ToolContext) = ToolAvailability.Available
            override suspend fun execute(input: JsonObject, context: ToolContext): String {
                executions += 1
                error("write may already have happened")
            }
        }

        val result = runtime().execute(registered(tool), "{}", context, "modify-run", maxRetries = 3)

        assertFalse(result.success)
        assertFalse(result.isRetryable)
        assertEquals(1, executions)
    }

    private fun runtime() = ToolRuntime(
        CapabilityRegistry(),
        ApprovalEngine { ApprovalPolicy.ALLOW },
    )

    private fun <O : Any> registered(tool: AgentTool<JsonObject, O>) =
        RegisteredTool.typed(tool, JsonObject.serializer())

    private fun descriptor(
        timeoutMs: Long = 5_000L,
        riskLevel: RiskLevel = RiskLevel.SAFE,
    ) = ToolDescriptor(
        id = "execution-test-tool",
        displayName = "Execution test tool",
        description = "Exercises retry and timeout semantics",
        inputSchema = """{"type":"object"}""",
        outputSchema = """{"type":"string"}""",
        riskLevel = riskLevel,
        timeoutMs = timeoutMs,
    )

    private companion object {
        val context = ToolContext(runId = "run", workspaceId = "workspace")
    }
}
