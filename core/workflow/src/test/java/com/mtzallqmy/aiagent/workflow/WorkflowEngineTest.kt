package com.mtzallqmy.aiagent.workflow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowEngineTest {
    @Test
    fun `persists outputs and execution timeline`() = runTest {
        val store = TestWorkflowStore()
        val engine = engine(store, action = { _, _ -> error("No external action expected") })
        val definition = WorkflowDefinition(
            id = "simple",
            version = 1,
            name = "Simple",
            entryNodeId = "start",
            nodes = listOf(
                TriggerNode("start"),
                TransformNode("transform", mapOf("copy" to WorkflowValue.RunInput("value"))),
                OutputNode("output", WorkflowValue.NodeOutput("transform", "copy")),
            ),
            edges = listOf(
                WorkflowEdge("start", "transform"),
                WorkflowEdge("transform", "output"),
            ),
        )

        val runId = engine.start(definition, JsonObject(mapOf("value" to JsonPrimitive("ok"))))
        advanceUntilIdle()
        val run = store.getRun(runId)!!

        assertEquals(WorkflowRunStatus.COMPLETED, run.status)
        assertEquals(JsonPrimitive("ok"), run.output)
        assertEquals(JsonPrimitive("ok"), run.nodeOutputs["transform"]?.get("copy"))
        assertTrue(run.timeline.any { it.label == "node-completed" && it.nodeId == "transform" })
    }

    @Test
    fun `retries an external node with a stable idempotency key`() = runTest {
        val attempts = AtomicInteger()
        val keys = mutableSetOf<String>()
        val store = TestWorkflowStore()
        val engine = engine(store) { _, context ->
            keys += context.idempotencyKey
            if (attempts.incrementAndGet() == 1) error("transient")
            JsonObject(mapOf("ok" to JsonPrimitive(true)))
        }
        val definition = toolWorkflow(ToolNode("tool", "http", retry = RetryPolicy(maxAttempts = 2)))

        val runId = engine.start(definition, JsonObject(emptyMap()))
        advanceUntilIdle()

        assertEquals(WorkflowRunStatus.COMPLETED, store.getRun(runId)?.status)
        assertEquals(2, attempts.get())
        assertEquals(1, keys.size)
    }

    @Test
    fun `node timeout fails the run after retries are exhausted`() = runTest {
        val store = TestWorkflowStore()
        val engine = engine(store) { _, _ ->
            delay(1_000)
            JsonObject(emptyMap())
        }
        val definition = toolWorkflow(ToolNode("tool", "slow", timeoutMillis = 100))

        val runId = engine.start(definition, JsonObject(emptyMap()))
        advanceUntilIdle()

        val run = store.getRun(runId)!!
        assertEquals(WorkflowRunStatus.FAILED, run.status)
        assertTrue(run.error?.contains("timed out", ignoreCase = true) == true)
    }

    @Test
    fun `parallel branches execute concurrently and join once`() = runTest {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val store = TestWorkflowStore()
        val engine = engine(store) { _, _ ->
            val current = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, current) }
            delay(100)
            active.decrementAndGet()
            JsonObject(mapOf("done" to JsonPrimitive(true)))
        }
        val definition = WorkflowDefinition(
            "parallel", 1, "Parallel", "start",
            nodes = listOf(
                TriggerNode("start"), ParallelNode("fork"),
                ToolNode("a", "a"), ToolNode("b", "b"),
                OutputNode("output", WorkflowValue.Literal(JsonPrimitive("joined"))),
            ),
            edges = listOf(
                WorkflowEdge("start", "fork"),
                WorkflowEdge("fork", "a", "branch"),
                WorkflowEdge("fork", "b", "branch"),
                WorkflowEdge("fork", "output", "done"),
                WorkflowEdge("a", "fork"),
                WorkflowEdge("b", "fork"),
            ),
        )

        val runId = engine.start(definition, JsonObject(emptyMap()))
        advanceUntilIdle()

        assertEquals(2, maximum.get())
        assertEquals(WorkflowRunStatus.COMPLETED, store.getRun(runId)?.status)
        assertEquals(1, store.getRun(runId)?.timeline?.count { it.label == "run-completed" })
    }

    @Test
    fun `loop reevaluates persisted node output and exits`() = runTest {
        val calls = AtomicInteger()
        val store = TestWorkflowStore()
        val engine = engine(store) { _, _ ->
            calls.incrementAndGet()
            JsonObject(mapOf("continue" to JsonPrimitive(false), "value" to JsonPrimitive("done")))
        }
        val predicate = WorkflowPredicate(
            WorkflowValue.NodeOutput("body", "continue"),
            PredicateOperator.NOT_EQUALS,
            WorkflowValue.Literal(JsonPrimitive(false)),
        )
        val definition = WorkflowDefinition(
            "loop", 1, "Loop", "start",
            nodes = listOf(
                TriggerNode("start"), LoopNode("loopNode", predicate, maxIterations = 3),
                ToolNode("body", "counter"),
                OutputNode("output", WorkflowValue.NodeOutput("body", "value")),
            ),
            edges = listOf(
                WorkflowEdge("start", "loopNode"),
                WorkflowEdge("loopNode", "body", "body"),
                WorkflowEdge("loopNode", "output", "done"),
                WorkflowEdge("body", "loopNode"),
            ),
        )

        val runId = engine.start(definition, JsonObject(emptyMap()))
        advanceUntilIdle()

        assertEquals(1, calls.get())
        assertEquals(JsonPrimitive("done"), store.getRun(runId)?.output)
    }

    @Test
    fun `pause blocks the next node and resume continues`() = runTest {
        val store = TestWorkflowStore()
        val engine = engine(store) { _, _ -> JsonObject(emptyMap()) }
        val definition = WorkflowDefinition(
            "pause", 1, "Pause", "start",
            listOf(TriggerNode("start"), DelayNode("delay", 1_000), OutputNode("output", WorkflowValue.Literal(JsonPrimitive(true)))),
            listOf(WorkflowEdge("start", "delay"), WorkflowEdge("delay", "output")),
        )

        val runId = engine.start(definition, JsonObject(emptyMap()))
        runCurrent()
        engine.pause(runId)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(WorkflowRunStatus.PAUSED, store.getRun(runId)?.status)

        engine.resume(runId)
        advanceUntilIdle()
        assertEquals(WorkflowRunStatus.COMPLETED, store.getRun(runId)?.status)
    }

    @Test
    fun `cancellation interrupts active delay and persists cancellation`() = runTest {
        val store = TestWorkflowStore()
        val engine = engine(store) { _, _ -> JsonObject(emptyMap()) }
        val definition = WorkflowDefinition(
            "cancel", 1, "Cancel", "start",
            listOf(TriggerNode("start"), DelayNode("delay", 60_000), OutputNode("output", WorkflowValue.Literal(JsonPrimitive(true)))),
            listOf(WorkflowEdge("start", "delay"), WorkflowEdge("delay", "output")),
        )

        val runId = engine.start(definition, JsonObject(emptyMap()))
        runCurrent()
        engine.cancel(runId)

        assertEquals(WorkflowRunStatus.CANCELLED, store.getRun(runId)?.status)
    }

    @Test
    fun `crash recovery resumes the persisted token with the same idempotency key`() = runTest {
        val store = TestWorkflowStore()
        val definition = toolWorkflow(ToolNode("tool", "recover"))
        store.saveDefinition(definition)
        val now = 1L
        store.createRun(
            WorkflowRun(
                "run", definition.id, definition.version, WorkflowRunStatus.RUNNING,
                JsonObject(emptyMap()), listOf(ExecutionToken("stable-token", "tool")),
                createdAtMillis = now, updatedAtMillis = now,
            ),
        )
        var key: String? = null
        val engine = engine(store) { _, context ->
            key = context.idempotencyKey
            JsonObject(mapOf("ok" to JsonPrimitive(true)))
        }

        assertEquals(listOf("run"), engine.recoverIncompleteRuns())
        advanceUntilIdle()

        assertEquals("run:stable-token", key)
        assertEquals(WorkflowRunStatus.COMPLETED, store.getRun("run")?.status)
    }

    private fun TestScope.engine(
        store: WorkflowStore,
        action: suspend (WorkflowNode, WorkflowExecutionContext) -> JsonObject,
    ) = WorkflowEngine(
        store = store,
        actionExecutor = object : WorkflowActionExecutor {
            override suspend fun execute(node: WorkflowNode, context: WorkflowExecutionContext) = action(node, context)
        },
        // Use the TestScope itself: backgroundScope jobs are deliberately excluded
        // from advanceUntilIdle and would leave assertions observing QUEUED runs.
        scope = this,
    )

    private fun toolWorkflow(tool: ToolNode) = WorkflowDefinition(
        "toolFlow", 1, "Tool", "start",
        listOf(TriggerNode("start"), tool, OutputNode("output", WorkflowValue.Literal(JsonPrimitive("ok")))),
        listOf(WorkflowEdge("start", tool.id), WorkflowEdge(tool.id, "output")),
    )
}

private class TestWorkflowStore : WorkflowStore {
    private val mutex = Mutex()
    private val definitions = mutableMapOf<Pair<String, Int>, WorkflowDefinition>()
    private val runs = mutableMapOf<String, WorkflowRun>()

    override suspend fun saveDefinition(definition: WorkflowDefinition) = mutex.withLock {
        definitions[definition.id to definition.version] = definition
    }
    override suspend fun getDefinition(id: String, version: Int) = mutex.withLock { definitions[id to version] }
    override suspend fun createRun(run: WorkflowRun) = mutex.withLock {
        require(runs.putIfAbsent(run.id, run) == null)
    }
    override suspend fun updateRun(run: WorkflowRun) = mutex.withLock {
        require(run.id in runs)
        runs[run.id] = run
    }
    override suspend fun getRun(runId: String) = mutex.withLock { runs[runId] }
    override suspend fun listRuns(statuses: Set<WorkflowRunStatus>) = mutex.withLock {
        runs.values.filter { statuses.isEmpty() || it.status in statuses }
    }
}
