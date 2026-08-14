package com.mtzallqmy.aiagent.tools

import com.mtzallqmy.aiagent.capabilities.CapabilityRegistry
import com.mtzallqmy.aiagent.model.ApprovalPolicy
import com.mtzallqmy.aiagent.model.RiskLevel
import com.mtzallqmy.aiagent.model.ToolDescriptor
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedToolRegistryTest {

    @Test
    fun `validated JSON is decoded to specific DTO before execute`() = runTest {
        val tool = FileWriteLikeTool()
        val registered = RegisteredTool.typed(tool, FileWriteLikeInput.serializer())
        val runtime = ToolRuntime(CapabilityRegistry(), ApprovalEngine { ApprovalPolicy.ALLOW })

        val result = runtime.execute(
            tool = registered,
            input = """{"path":"notes.txt","content":"hello"}""",
            context = ToolContext("run", "workspace"),
            runId = "run",
        )

        assertTrue(result.success)
        assertEquals(FileWriteLikeInput("notes.txt", "hello"), tool.received)
    }

    @Test
    fun `serialization rejects unknown properties and never executes`() = runTest {
        val tool = FileWriteLikeTool()
        val registered = RegisteredTool.typed(tool, FileWriteLikeInput.serializer())
        val runtime = ToolRuntime(CapabilityRegistry(), ApprovalEngine { ApprovalPolicy.ALLOW })

        val result = runtime.execute(
            tool = registered,
            input = """{"path":"notes.txt","content":"hello","unexpected":true}""",
            context = ToolContext("run", "workspace"),
            runId = "run",
        )

        assertFalse(result.success)
        assertFalse(result.isRetryable)
        assertEquals(null, tool.received)
    }

    @Test
    fun `registry rejects duplicate tool ids and exposes schemas`() {
        val registry = TypedToolRegistry()
        val tool = RegisteredTool.typed(FileWriteLikeTool(), FileWriteLikeInput.serializer())
        registry.register(tool)

        assertEquals(listOf("typed.write"), registry.descriptors().map { it.id })
        try {
            registry.register(tool)
            throw AssertionError("Expected duplicate registration to fail")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Serializable
    private data class FileWriteLikeInput(val path: String, val content: String)

    private class FileWriteLikeTool : AgentTool<FileWriteLikeInput, String> {
        var received: FileWriteLikeInput? = null
        override val descriptor = ToolDescriptor(
            id = "typed.write",
            displayName = "Typed write",
            description = "Typed test tool",
            inputSchema = """{"type":"object","required":["path","content"],"properties":{"path":{"type":"string"},"content":{"type":"string"}}}""",
            outputSchema = """{"type":"string"}""",
            riskLevel = RiskLevel.MODIFY,
        )

        override suspend fun availability(context: ToolContext): ToolAvailability = ToolAvailability.Available

        override suspend fun execute(input: FileWriteLikeInput, context: ToolContext): String {
            received = input
            return "ok"
        }
    }
}
