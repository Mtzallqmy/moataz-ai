package com.mtzallqmy.aiagent.workflow

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowValidatorTest {
    @Test
    fun `rejects graph cycles that do not return through Loop or Parallel`() {
        val definition = WorkflowDefinition(
            "bad", 1, "Bad", "start",
            listOf(
                TriggerNode("start"), TransformNode("a", emptyMap()), TransformNode("b", emptyMap()),
                OutputNode("output", WorkflowValue.Literal(JsonPrimitive(true))),
            ),
            listOf(
                WorkflowEdge("start", "a"), WorkflowEdge("a", "b"), WorkflowEdge("b", "a"),
            ),
        )
        val error = assertThrows(WorkflowValidationException::class.java) {
            WorkflowValidator().validate(definition)
        }
        assertTrue(error.violations.any { it.contains("cycle") })
        assertTrue(error.violations.any { it.contains("unreachable") })
    }

    @Test
    fun `rejects malformed parallel edges`() {
        val definition = WorkflowDefinition(
            "badParallel", 1, "Bad", "start",
            listOf(TriggerNode("start"), ParallelNode("fork"), OutputNode("output", WorkflowValue.Literal(JsonPrimitive(true)))),
            listOf(WorkflowEdge("start", "fork"), WorkflowEdge("fork", "output", "done")),
        )
        val error = assertThrows(WorkflowValidationException::class.java) {
            WorkflowValidator().validate(definition)
        }
        assertTrue(error.violations.any { it.contains("at least two") })
    }

    @Test
    fun `rejects references to missing node outputs`() {
        val definition = WorkflowDefinition(
            "badReference", 1, "Bad", "start",
            listOf(TriggerNode("start"), OutputNode("output", WorkflowValue.NodeOutput("missing"))),
            listOf(WorkflowEdge("start", "output")),
        )
        val error = assertThrows(WorkflowValidationException::class.java) {
            WorkflowValidator().validate(definition)
        }
        assertTrue(error.violations.any { it.contains("missing output") })
    }
}
