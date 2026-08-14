package com.mtzallqmy.aiagent.agent

import com.mtzallqmy.aiagent.model.ChatMessage
import com.mtzallqmy.aiagent.model.MessageRole
import com.mtzallqmy.aiagent.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {
    @Test
    fun `drops complete old turns without orphaning tool result`() {
        val manager = ContextManager(contextWindow = 700, reserveForResponse = 100)
        val oldCall = ToolCall("call-1", "read", "{\"path\":\"a\"}")
        val messages = listOf(
            ChatMessage(role = MessageRole.SYSTEM, content = "system"),
            ChatMessage(role = MessageRole.USER, content = "old question " + "x".repeat(3000)),
            ChatMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(oldCall)),
            ChatMessage(role = MessageRole.TOOL, content = "old result", toolCallId = "call-1", toolName = "read"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "old answer"),
            ChatMessage(role = MessageRole.USER, content = "new question"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "new answer"),
        )

        val fitted = manager.fit(messages)

        assertTrue(fitted.any { it.role == MessageRole.SYSTEM })
        assertTrue(fitted.any { it.content == "new question" })
        assertFalse(fitted.any { it.toolCallId == "call-1" })
        assertFalse(fitted.any { it.toolCalls.any { call -> call.id == "call-1" } })
    }

    @Test
    fun `compresses large tool payload before provider request`() {
        val manager = ContextManager(contextWindow = 4096, maxToolOutputChars = 32)
        val fitted = manager.fit(
            listOf(
                ChatMessage(role = MessageRole.USER, content = "go"),
                ChatMessage(role = MessageRole.TOOL, content = "z".repeat(200), toolCallId = "c"),
            ),
        )
        val tool = fitted.first { it.role == MessageRole.TOOL }
        assertTrue(tool.content.length < 100)
        assertTrue(tool.content.contains("truncated"))
    }

    @Test
    fun `deduplicates only identical adjacent correlated tool results`() {
        val manager = ContextManager()
        val one = ChatMessage(role = MessageRole.TOOL, content = "same", toolCallId = "c")
        val fitted = manager.fit(
            listOf(
                ChatMessage(role = MessageRole.USER, content = "go"),
                one,
                one.copy(id = "another"),
            ),
        )
        assertEquals(1, fitted.count { it.role == MessageRole.TOOL })
    }
}
