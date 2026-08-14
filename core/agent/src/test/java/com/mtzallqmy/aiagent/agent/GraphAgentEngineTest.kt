package com.mtzallqmy.aiagent.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GraphAgentEngineTest {

    private fun simpleEngine(): GraphAgentEngine<Int> =
        GraphAgentEngine(entryNode = "start") { nodeId, state ->
            when (nodeId) {
                "start" -> GraphAgentEngine.GraphNextStep.Goto("work", state + 1)
                "work" -> GraphAgentEngine.GraphNextStep.Goto("done", state * 2)
                else -> GraphAgentEngine.GraphNextStep.End(state)
            }
        }

    @Test
    fun `engine walks nodes and ends with final state`() = runBlocking {
        val engine = simpleEngine()
        val trace = engine.run(initialState = 3)
        assertNull(trace.interruptedAt)
        assertEquals(8, trace.endState) // 3+1=4, 4*2=8
        assertEquals(listOf("start", "work", "done"), trace.checkpoints.map { it.nodeId })
    }

    @Test
    fun `interrupt-before halts at review node`() = runBlocking {
        val engine = simpleEngine()
        engine.interruptBefore = setOf("done")
        val trace = engine.run(initialState = 3)
        assertEquals("done", trace.interruptedAt)
        assertNull(trace.endState)
    }

    @Test
    fun `resume continues with approved state after interrupt`() = runBlocking {
        val engine = simpleEngine()
        engine.interruptBefore = setOf("done")
        val first = engine.run(initialState = 3)
        assertEquals("done", first.interruptedAt)
        // Approve a corrected state (99) and let it end
        val second = engine.resume(approvedState = 99, interruptedNode = "done")
        assertNotNull(second)
        // Resume re-runs the graph with the approved state: 99+1=100 at start,
        // then 100*2=200 at work, then End(200) at done (interrupt skipped).
        assertEquals(200, second!!.endState)
    }

    @Test
    fun `maxSteps guard marks interruptedAt`() = runBlocking {
        val cyclic = GraphAgentEngine<String>(entryNode = "a") { nodeId, state ->
            when (nodeId) {
                "a" -> GraphAgentEngine.GraphNextStep.Goto("b", state)
                else -> GraphAgentEngine.GraphNextStep.Goto("a", state)
            }
        }
        val trace = cyclic.run(initialState = "x", maxSteps = 10)
        assertEquals("max_steps", trace.interruptedAt)
    }
}
