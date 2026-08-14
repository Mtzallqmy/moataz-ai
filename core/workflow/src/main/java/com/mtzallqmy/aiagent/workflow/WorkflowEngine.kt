package com.mtzallqmy.aiagent.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.pow

data class WorkflowExecutionContext(
    val runId: String,
    val workflowId: String,
    val workflowVersion: Int,
    val nodeId: String,
    val attempt: Int,
    /** Stable across crash retries; external executors must deduplicate on this key. */
    val idempotencyKey: String,
    val runInput: JsonObject,
    val nodeOutputs: Map<String, JsonObject>,
)

interface WorkflowActionExecutor {
    /** Executes Agent, Tool, Approval, or Notification nodes against real adapters. */
    suspend fun execute(node: WorkflowNode, context: WorkflowExecutionContext): JsonObject
}

class WorkflowEngine(
    private val store: WorkflowStore,
    private val actionExecutor: WorkflowActionExecutor,
    private val validator: WorkflowValidator = WorkflowValidator(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val mutationMutexes = ConcurrentHashMap<String, Mutex>()
    private val mutableEvents = MutableSharedFlow<Pair<String, WorkflowTimelineEvent>>(extraBufferCapacity = 128)
    val events = mutableEvents.asSharedFlow()

    suspend fun start(definition: WorkflowDefinition, input: JsonObject): String {
        validator.validate(definition)
        requireJsonSize(input, "workflow input")
        store.saveDefinition(definition)
        val runId = UUID.randomUUID().toString()
        val now = nowMillis()
        val run = WorkflowRun(
            id = runId,
            workflowId = definition.id,
            workflowVersion = definition.version,
            status = WorkflowRunStatus.QUEUED,
            input = input,
            pendingTokens = listOf(ExecutionToken(UUID.randomUUID().toString(), definition.entryNodeId)),
            timeline = listOf(WorkflowTimelineEvent(null, "run-created", now)),
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        store.createRun(run)
        launchRun(runId)
        return runId
    }

    suspend fun startStored(workflowId: String, workflowVersion: Int, input: JsonObject): String {
        val definition = store.getDefinition(workflowId, workflowVersion)
            ?: throw IllegalArgumentException("Workflow definition not found: $workflowId@$workflowVersion")
        return start(definition, input)
    }

    suspend fun pause(runId: String) = mutateRun(runId) { run ->
        require(run.status == WorkflowRunStatus.RUNNING || run.status == WorkflowRunStatus.QUEUED) {
            "Run cannot be paused from ${run.status}"
        }
        run.copy(
            status = WorkflowRunStatus.PAUSED,
            timeline = run.timeline + WorkflowTimelineEvent(null, "run-paused", nowMillis()),
            updatedAtMillis = nowMillis(),
        )
    }

    suspend fun resume(runId: String) {
        mutateRun(runId) { run ->
            require(run.status == WorkflowRunStatus.PAUSED) { "Run is not paused" }
            run.copy(
                status = WorkflowRunStatus.RUNNING,
                timeline = run.timeline + WorkflowTimelineEvent(null, "run-resumed", nowMillis()),
                updatedAtMillis = nowMillis(),
            )
        }
        launchRun(runId)
    }

    suspend fun cancel(runId: String) {
        jobs[runId]?.cancelAndJoin()
        mutateRun(runId) { run ->
            if (run.status in TERMINAL_STATUSES) run else run.copy(
                status = WorkflowRunStatus.CANCELLED,
                timeline = run.timeline + WorkflowTimelineEvent(null, "run-cancelled", nowMillis()),
                updatedAtMillis = nowMillis(),
            )
        }
    }

    /** Relaunches RUNNING/QUEUED runs; PAUSED runs remain paused until explicit resume. */
    suspend fun recoverIncompleteRuns(): List<String> {
        val recoverable = store.listRuns(setOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.QUEUED))
        recoverable.forEach { run ->
            requireNotNull(store.getDefinition(run.workflowId, run.workflowVersion)) {
                "Missing workflow definition for run ${run.id}"
            }
            launchRun(run.id)
        }
        return recoverable.map { it.id }
    }

    suspend fun getRun(runId: String): WorkflowRun? = store.getRun(runId)

    private fun launchRun(runId: String) {
        if (jobs[runId]?.isActive == true) return
        jobs[runId] = scope.launch {
            try {
                executeRun(runId)
            } finally {
                jobs.remove(runId)
            }
        }
    }

    private suspend fun executeRun(runId: String) {
        try {
            mutateRun(runId) { run ->
                if (run.status == WorkflowRunStatus.QUEUED) run.copy(
                    status = WorkflowRunStatus.RUNNING,
                    timeline = run.timeline + WorkflowTimelineEvent(null, "run-started", nowMillis()),
                    updatedAtMillis = nowMillis(),
                ) else run
            }
            while (true) {
                var run = requireNotNull(store.getRun(runId))
                if (run.status == WorkflowRunStatus.PAUSED) {
                    delay(PAUSE_POLL_MILLIS)
                    continue
                }
                if (run.status in TERMINAL_STATUSES) return
                val definition = requireNotNull(store.getDefinition(run.workflowId, run.workflowVersion))
                if (run.pendingTokens.isEmpty()) {
                    finishWithoutPending(run)
                    return
                }

                val batch = selectBatch(run.pendingTokens)
                val outcomes = coroutineScope {
                    batch.map { token -> async { executeToken(definition, run, token) } }.awaitAll()
                }
                applyOutcomes(definition, runId, batch, outcomes)
                run = requireNotNull(store.getRun(runId))
                if (run.status in TERMINAL_STATUSES) return
            }
        } catch (cancelled: CancellationException) {
            val current = store.getRun(runId)
            if (current != null && current.status !in TERMINAL_STATUSES) {
                store.updateRun(
                    current.copy(
                        status = WorkflowRunStatus.CANCELLED,
                        timeline = current.timeline + WorkflowTimelineEvent(null, "run-cancelled", nowMillis()),
                        updatedAtMillis = nowMillis(),
                    ),
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            val current = store.getRun(runId) ?: return
            if (current.status !in TERMINAL_STATUSES) {
                val message = error.message?.take(MAX_ERROR_LENGTH) ?: error::class.java.simpleName
                store.updateRun(
                    current.copy(
                        status = WorkflowRunStatus.FAILED,
                        error = message,
                        timeline = current.timeline + WorkflowTimelineEvent(null, "run-failed", nowMillis(), error = message),
                        updatedAtMillis = nowMillis(),
                    ),
                )
            }
        }
    }

    private fun selectBatch(tokens: List<ExecutionToken>): List<ExecutionToken> {
        val first = tokens.first()
        val groupId = first.parallelFrames.lastOrNull()?.groupId ?: return listOf(first)
        return tokens.filter { it.parallelFrames.lastOrNull()?.groupId == groupId }
    }

    private suspend fun executeToken(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        token: ExecutionToken,
    ): TokenOutcome {
        val node = definition.nodes.first { it.id == token.nodeId }
        val returningFrame = token.parallelFrames.lastOrNull()?.takeIf { frame ->
            node is ParallelNode && frame.parallelNodeId == node.id
        }
        if (returningFrame != null) return TokenOutcome(token, arrivedFrame = returningFrame)

        val execution = executeWithRetry(node, run, token)
        val outgoing = definition.edges.filter { it.from == node.id }
        return when (node) {
            is OutputNode -> TokenOutcome(token, nodeOutput = execution.output, runOutput = execution.value)
            is ConditionNode -> TokenOutcome(
                token,
                nodeOutput = execution.output,
                newTokens = listOf(token.next(outgoing.single { it.label == if (execution.predicate) "true" else "false" }.to)),
                timeline = execution.timeline,
            )
            is LoopNode -> {
                val iterations = token.loopIterations[node.id] ?: 0
                if (execution.predicate && iterations >= node.maxIterations) {
                    throw IllegalStateException("Loop ${node.id} exceeded maxIterations=${node.maxIterations}")
                }
                val edgeLabel = if (execution.predicate) "body" else "done"
                val nextIterations = if (execution.predicate) {
                    token.loopIterations + (node.id to iterations + 1)
                } else token.loopIterations - node.id
                TokenOutcome(
                    token,
                    nodeOutput = execution.output,
                    newTokens = listOf(token.next(outgoing.single { it.label == edgeLabel }.to, nextIterations)),
                    timeline = execution.timeline,
                )
            }
            is ParallelNode -> {
                val branchEdges = outgoing.filter { it.label == "branch" }
                val groupId = UUID.randomUUID().toString()
                val group = ParallelGroupState(
                    id = groupId,
                    nodeId = node.id,
                    expectedBranches = branchEdges.size,
                    parentFrames = token.parallelFrames,
                    loopIterations = token.loopIterations,
                )
                val branches = branchEdges.mapIndexed { index, edge ->
                    token.next(edge.to).copy(
                        parallelFrames = token.parallelFrames + ParallelFrame(groupId, node.id, index),
                    )
                }
                TokenOutcome(
                    token,
                    nodeOutput = execution.output,
                    newTokens = branches,
                    newParallelGroup = group,
                    timeline = execution.timeline,
                )
            }
            else -> TokenOutcome(
                token,
                nodeOutput = execution.output,
                newTokens = listOf(token.next(outgoing.single().to)),
                timeline = execution.timeline,
            )
        }.let { outcome -> if (outcome.timeline.isEmpty()) outcome.copy(timeline = execution.timeline) else outcome }
    }

    private suspend fun executeWithRetry(
        node: WorkflowNode,
        run: WorkflowRun,
        token: ExecutionToken,
    ): NodeExecution {
        var lastError: Throwable? = null
        for (attempt in 1..node.retry.maxAttempts) {
            val start = WorkflowTimelineEvent(node.id, "node-started", nowMillis(), attempt)
            recordTimeline(run.id, start)
            try {
                val result = withTimeout(node.timeoutMillis) { executeNode(node, run, token, attempt) }
                requireJsonSize(result.output, "node ${node.id} output")
                if (node is OutputNode) requireJsonSize(result.value, "workflow output")
                val end = WorkflowTimelineEvent(node.id, "node-completed", nowMillis(), attempt)
                recordTimeline(run.id, end)
                return result
            } catch (timeout: TimeoutCancellationException) {
                val message = "Node timed out after ${node.timeoutMillis} ms"
                lastError = IllegalStateException(message, timeout)
                recordTimeline(
                    run.id,
                    WorkflowTimelineEvent(node.id, "node-attempt-failed", nowMillis(), attempt, message),
                )
                if (attempt == node.retry.maxAttempts) break
                delay(retryDelay(node.retry, attempt))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                val message = error.message?.take(MAX_ERROR_LENGTH) ?: error::class.java.simpleName
                val failed = WorkflowTimelineEvent(node.id, "node-attempt-failed", nowMillis(), attempt, message)
                recordTimeline(run.id, failed)
                if (attempt == node.retry.maxAttempts) break
                delay(retryDelay(node.retry, attempt))
            }
        }
        throw lastError ?: IllegalStateException("Node failed without an error")
    }

    private fun retryDelay(policy: RetryPolicy, attempt: Int): Long = min(
        policy.maxDelayMillis.toDouble(),
        policy.initialDelayMillis * policy.multiplier.pow((attempt - 1).toDouble()),
    ).toLong()

    private suspend fun recordTimeline(runId: String, event: WorkflowTimelineEvent) {
        mutableEvents.tryEmit(runId to event)
        mutateRun(runId) { run -> run.copy(
            timeline = run.timeline + event,
            updatedAtMillis = nowMillis(),
        ) }
    }

    private suspend fun executeNode(
        node: WorkflowNode,
        run: WorkflowRun,
        token: ExecutionToken,
        attempt: Int,
    ): NodeExecution {
        val resolver = WorkflowValueResolver(run.input, run.nodeOutputs)
        return when (node) {
            is TriggerNode -> NodeExecution(run.input)
            is ConditionNode -> NodeExecution(
                output = JsonObject(mapOf("result" to JsonPrimitive(resolver.evaluate(node.predicate)))),
                predicate = resolver.evaluate(node.predicate),
            )
            is LoopNode -> NodeExecution(
                output = JsonObject(mapOf("result" to JsonPrimitive(resolver.evaluate(node.predicate)))),
                predicate = resolver.evaluate(node.predicate),
            )
            is ParallelNode -> NodeExecution(JsonObject(emptyMap()))
            is DelayNode -> {
                delay(node.delayMillis)
                NodeExecution(JsonObject(mapOf("delayedMillis" to JsonPrimitive(node.delayMillis))))
            }
            is TransformNode -> NodeExecution(
                JsonObject(node.assignments.mapValues { (_, value) -> resolver.resolve(value) }),
            )
            is OutputNode -> {
                val value = resolver.resolve(node.value)
                NodeExecution(JsonObject(mapOf("value" to value)), value = value)
            }
            is AgentNode, is ToolNode, is ApprovalNode, is NotificationNode -> NodeExecution(
                actionExecutor.execute(
                    node,
                    WorkflowExecutionContext(
                        runId = run.id,
                        workflowId = run.workflowId,
                        workflowVersion = run.workflowVersion,
                        nodeId = node.id,
                        attempt = attempt,
                        idempotencyKey = "${run.id}:${token.id}",
                        runInput = run.input,
                        nodeOutputs = run.nodeOutputs,
                    ),
                ).also { requireJsonSize(it, "node ${node.id} output") },
            )
        }
    }

    private suspend fun applyOutcomes(
        definition: WorkflowDefinition,
        runId: String,
        batch: List<ExecutionToken>,
        outcomes: List<TokenOutcome>,
    ) = mutateRun(runId) { current ->
        val removed = batch.map { it.id }.toSet()
        var pending = current.pendingTokens.filterNot { it.id in removed }
        var outputs = current.nodeOutputs
        var groups = current.parallelGroups
        var output = current.output
        var timeline = current.timeline

        outcomes.forEach { outcome ->
            outcome.nodeOutput?.let { outputs = outputs + (outcome.token.nodeId to it) }
            outcome.newParallelGroup?.let { groups = groups + (it.id to it) }
            pending = pending + outcome.newTokens
            outcome.runOutput?.let { output = it }
            timeline = timeline + outcome.timeline

            outcome.arrivedFrame?.let { frame ->
                val group = requireNotNull(groups[frame.groupId]) { "Missing parallel group ${frame.groupId}" }
                val updated = group.copy(arrivedBranches = group.arrivedBranches + frame.branchIndex)
                if (updated.arrivedBranches.size == updated.expectedBranches) {
                    groups = groups - group.id
                    val done = definition.edges.single { it.from == group.nodeId && it.label == "done" }
                    pending = pending + ExecutionToken(
                        id = UUID.randomUUID().toString(),
                        nodeId = done.to,
                        loopIterations = group.loopIterations,
                        parallelFrames = group.parentFrames,
                    )
                } else {
                    groups = groups + (group.id to updated)
                }
            }
        }

        require(pending.size <= MAX_PENDING_TOKENS) { "Workflow exceeded $MAX_PENDING_TOKENS pending tokens" }
        require(timeline.size <= MAX_TIMELINE_EVENTS) { "Workflow exceeded $MAX_TIMELINE_EVENTS timeline events" }

        val complete = pending.isEmpty() && output != null
        current.copy(
            status = if (complete) WorkflowRunStatus.COMPLETED else current.status,
            pendingTokens = pending,
            nodeOutputs = outputs,
            parallelGroups = groups,
            timeline = if (complete) timeline + WorkflowTimelineEvent(null, "run-completed", nowMillis()) else timeline,
            output = output,
            updatedAtMillis = nowMillis(),
        )
    }

    private suspend fun finishWithoutPending(run: WorkflowRun) {
        if (run.output != null) {
            mutateRun(run.id) { current -> current.copy(
                status = WorkflowRunStatus.COMPLETED,
                timeline = current.timeline + WorkflowTimelineEvent(null, "run-completed", nowMillis()),
                updatedAtMillis = nowMillis(),
            ) }
        } else {
            throw IllegalStateException("Workflow ended without an Output node")
        }
    }

    private suspend fun mutateRun(runId: String, mutation: (WorkflowRun) -> WorkflowRun) {
        mutationMutexes.getOrPut(runId) { Mutex() }.withLock {
            val current = requireNotNull(store.getRun(runId)) { "Workflow run not found: $runId" }
            store.updateRun(mutation(current))
        }
    }

    private fun requireJsonSize(value: JsonElement, label: String) {
        require(value.toString().encodeToByteArray().size <= MAX_JSON_BYTES) {
            "$label exceeds $MAX_JSON_BYTES bytes"
        }
    }

    private fun ExecutionToken.next(target: String, iterations: Map<String, Int> = loopIterations) = copy(
        id = UUID.randomUUID().toString(),
        nodeId = target,
        loopIterations = iterations,
    )

    private data class NodeExecution(
        val output: JsonObject,
        val predicate: Boolean = false,
        val value: JsonElement = JsonNull,
        val timeline: List<WorkflowTimelineEvent> = emptyList(),
    )

    private data class TokenOutcome(
        val token: ExecutionToken,
        val nodeOutput: JsonObject? = null,
        val newTokens: List<ExecutionToken> = emptyList(),
        val newParallelGroup: ParallelGroupState? = null,
        val arrivedFrame: ParallelFrame? = null,
        val runOutput: JsonElement? = null,
        val timeline: List<WorkflowTimelineEvent> = emptyList(),
    )

    private companion object {
        val TERMINAL_STATUSES = setOf(
            WorkflowRunStatus.COMPLETED,
            WorkflowRunStatus.FAILED,
            WorkflowRunStatus.CANCELLED,
        )
        const val PAUSE_POLL_MILLIS = 50L
        const val MAX_ERROR_LENGTH = 2_000
        const val MAX_JSON_BYTES = 1024 * 1024
        const val MAX_PENDING_TOKENS = 10_000
        const val MAX_TIMELINE_EVENTS = 50_000
    }
}

internal class WorkflowValueResolver(
    private val input: JsonObject,
    private val outputs: Map<String, JsonObject>,
) {
    fun resolve(value: WorkflowValue): JsonElement = when (value) {
        is WorkflowValue.Literal -> value.value
        is WorkflowValue.RunInput -> select(input, value.path)
        is WorkflowValue.NodeOutput -> select(
            outputs[value.nodeId] ?: throw IllegalStateException("Output is not available: ${value.nodeId}"),
            value.path,
        )
    }

    fun evaluate(predicate: WorkflowPredicate): Boolean {
        val left = runCatching { resolve(predicate.left) }.getOrNull()
        val right = predicate.right?.let { resolve(it) }
        return when (predicate.operator) {
            PredicateOperator.EXISTS -> left != null && left !is JsonNull
            PredicateOperator.NOT_EXISTS -> left == null || left is JsonNull
            PredicateOperator.EQUALS -> left == right
            PredicateOperator.NOT_EQUALS -> left != right
            PredicateOperator.TRUE -> (left as? JsonPrimitive)?.content == "true"
            PredicateOperator.FALSE -> (left as? JsonPrimitive)?.content == "false"
        }
    }

    private fun select(root: JsonElement, path: String?): JsonElement {
        if (path.isNullOrBlank()) return root
        return path.split('.').fold(root) { current, segment ->
            when (current) {
                is JsonObject -> current[segment]
                    ?: throw IllegalStateException("JSON path does not exist: $path")
                is JsonArray -> current.getOrNull(segment.toIntOrNull() ?: -1)
                    ?: throw IllegalStateException("JSON array path does not exist: $path")
                else -> throw IllegalStateException("JSON path crosses a scalar: $path")
            }
        }
    }
}
