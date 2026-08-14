package com.mtzallqmy.aiagent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun `agent state machine contains all required states`() {
        val expected = setOf(
            "IDLE", "THINKING", "PLANNING", "WAITING_FOR_TOOL", "EXECUTING_TOOL",
            "WAITING_FOR_APPROVAL", "OBSERVING", "REPLANNING", "PAUSED",
            "COMPLETED", "FAILED", "CANCELLED",
        )
        assertEquals(expected, AgentState.values().map { it.name }.toSet())
    }

    @Test
    fun `initial agent state is IDLE`() {
        assertEquals(AgentState.IDLE, AgentState.valueOf("IDLE"))
    }

    @Test
    fun `risk levels cover the full security spectrum`() {
        val expected = setOf("SAFE", "READ", "MODIFY", "COMMUNICATION", "FINANCIAL", "SYSTEM_SENSITIVE")
        assertEquals(expected, RiskLevel.values().map { it.name }.toSet())
    }

    @Test
    fun `risk level ordering reflects increasing danger`() {
        assertTrue(RiskLevel.SAFE.ordinal < RiskLevel.READ.ordinal)
        assertTrue(RiskLevel.READ.ordinal < RiskLevel.MODIFY.ordinal)
        assertTrue(RiskLevel.MODIFY.ordinal < RiskLevel.COMMUNICATION.ordinal)
        assertTrue(RiskLevel.COMMUNICATION.ordinal < RiskLevel.FINANCIAL.ordinal)
        assertTrue(RiskLevel.FINANCIAL.ordinal < RiskLevel.SYSTEM_SENSITIVE.ordinal)
    }

    @Test
    fun `approval policy values are exhaustive`() {
        assertEquals(4, ApprovalPolicy.values().size)
        setOf("ALLOW", "ASK_ONCE", "ASK_EVERY_TIME", "DENY").forEach { name ->
            assertEquals(name, ApprovalPolicy.valueOf(name).name)
        }
    }

    @Test
    fun `approval options are exhaustive`() {
        assertEquals(5, ApprovalOption.values().size)
        setOf("ALLOW_ONCE", "ALLOW_FOR_TASK", "ALWAYS_ALLOW", "DENY", "ASK").forEach { name ->
            assertEquals(name, ApprovalOption.valueOf(name).name)
        }
    }

    @Test
    fun `message roles include all four kinds`() {
        assertEquals(setOf("SYSTEM", "USER", "ASSISTANT", "TOOL"), MessageRole.values().map { it.name }.toSet())
    }

    @Test
    fun `AiModel carries provider and id`() {
        val model = AiModel(id = "gpt-4.1", providerId = "openai", name = "GPT-4.1")
        assertEquals("gpt-4.1", model.id)
        assertEquals("openai", model.providerId)
        assertNotEquals(AiModel(id = "x", providerId = "a", name = "X"), model)
    }
}
