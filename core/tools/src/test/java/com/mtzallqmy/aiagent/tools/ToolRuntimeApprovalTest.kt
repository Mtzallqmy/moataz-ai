package com.mtzallqmy.aiagent.tools

import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.model.ApprovalOption
import com.mtzallqmy.aiagent.model.ApprovalPolicy
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import com.mtzallqmy.aiagent.model.ToolErrorCategory
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRuntimeApprovalTest {

    @Test
    fun `ASK does not execute before decision`() = runTest {
        val engine = ApprovalEngine { ApprovalPolicy.ASK_EVERY_TIME }
        val tool = CountingTool()
        val execution = async { runtime(engine).execute(registered(tool), "{}", context, "run-ask") }

        val request = engine.requests.receive()
        runCurrent()
        assertEquals(0, tool.executionCount)
        assertFalse(execution.isCompleted)

        engine.respond(request.id, ApprovalOption.ALLOW_ONCE)
        assertTrue(execution.await().success)
        assertEquals(1, tool.executionCount)
    }

    @Test
    fun `ALLOW executes exactly once`() = runTest {
        val engine = ApprovalEngine { ApprovalPolicy.ALLOW }
        val tool = CountingTool()

        val result = runtime(engine).execute(registered(tool), "{}", context, "run-allow")

        assertTrue(result.success)
        assertEquals(1, tool.executionCount)
        assertNull(engine.requests.tryReceive().getOrNull())
    }

    @Test
    fun `DENY never executes`() = runTest {
        val engine = ApprovalEngine { ApprovalPolicy.DENY }
        val tool = CountingTool()

        val result = runtime(engine).execute(registered(tool), "{}", context, "run-deny")

        assertFalse(result.success)
        assertEquals(ToolErrorCategory.APPROVAL_REQUIRED, result.errorCategory)
        assertEquals(0, tool.executionCount)
        assertNull(engine.requests.tryReceive().getOrNull())
    }

    @Test
    fun `one tool call emits one approval request`() = runTest {
        val engine = ApprovalEngine { ApprovalPolicy.ASK_EVERY_TIME }
        val tool = CountingTool()
        val execution = async { runtime(engine).execute(registered(tool), "{}", context, "run-single-request") }

        val request = engine.requests.receive()
        assertNull(engine.requests.tryReceive().getOrNull())
        engine.respond(request.id, ApprovalOption.ALLOW_ONCE)

        assertTrue(execution.await().success)
        assertNull(engine.requests.tryReceive().getOrNull())
        assertEquals(1, tool.executionCount)
    }

    @Test
    fun `cancellation while waiting removes pending request and never executes`() = runTest {
        val engine = ApprovalEngine { ApprovalPolicy.ASK_EVERY_TIME }
        val tool = CountingTool()
        val execution = async { runtime(engine).execute(registered(tool), "{}", context, "run-cancel") }

        engine.requests.receive()
        assertEquals(1, engine.pendingCount)
        execution.cancelAndJoin()
        runCurrent()

        assertEquals(0, engine.pendingCount)
        assertEquals(0, tool.executionCount)
    }

    @Test
    fun `delegated risk scope denies before approval`() = runTest {
        val engine = ApprovalEngine { ApprovalPolicy.ASK_EVERY_TIME }
        val tool = CountingTool()

        val result = runtime(engine).execute(
            registered(tool),
            "{}",
            context.copy(allowedRiskLevels = setOf(RiskLevel.SAFE)),
            "run-delegated-policy",
        )

        assertFalse(result.success)
        assertEquals(ToolErrorCategory.POLICY_DENIED, result.errorCategory)
        assertEquals(0, tool.executionCount)
        assertNull(engine.requests.tryReceive().getOrNull())
    }

    private fun runtime(engine: ApprovalEngine) = ToolRuntime(CapabilityRegistry(), engine)

    private fun registered(tool: CountingTool) =
        RegisteredTool.typed(tool, kotlinx.serialization.json.JsonObject.serializer())

    private class CountingTool : AgentTool<kotlinx.serialization.json.JsonObject, Any> {
        var executionCount = 0

        override val descriptor = ToolDescriptor(
            id = "counting-tool",
            displayName = "Counting tool",
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

    private companion object {
        val context = ToolContext(runId = "run", workspaceId = "workspace")
    }
}
