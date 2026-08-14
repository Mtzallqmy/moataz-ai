package com.mtzallqmy.aiagent.workflow

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileWorkflowStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `definitions and runs survive store recreation`() = runTest {
        val file = temporaryFolder.newFile("workflow-state.json").apply { delete() }
        val definition = WorkflowDefinition(
            "persisted", 1, "Persisted", "start",
            listOf(TriggerNode("start"), OutputNode("output", WorkflowValue.Literal(JsonPrimitive(true)))),
            listOf(WorkflowEdge("start", "output")),
        )
        val run = WorkflowRun(
            "run", definition.id, definition.version, WorkflowRunStatus.PAUSED,
            JsonObject(mapOf("input" to JsonPrimitive("value"))),
            listOf(ExecutionToken("token", "output")),
            createdAtMillis = 1,
            updatedAtMillis = 2,
        )
        AtomicFileWorkflowStore(file).also { store ->
            store.saveDefinition(definition)
            store.createRun(run)
        }

        val reopened = AtomicFileWorkflowStore(file)

        assertEquals(definition, reopened.getDefinition("persisted", 1))
        assertEquals(run, reopened.getRun("run"))
        assertEquals(listOf(run), reopened.listRuns(setOf(WorkflowRunStatus.PAUSED)))
    }

    @Test
    fun `corrupt persistence is surfaced and never silently reset`() = runTest {
        val file = temporaryFolder.newFile("corrupt.json").apply { writeText("not-json") }
        val store = AtomicFileWorkflowStore(file)

        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { store.listRuns() }
        }

        assertEquals("not-json", file.readText())
    }
}
