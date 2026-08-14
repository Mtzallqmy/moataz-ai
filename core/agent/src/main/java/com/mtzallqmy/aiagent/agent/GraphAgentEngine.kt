package com.mtzallqmy.aiagent.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Graph-based agent engine — concepts studied from LangGraph (MIT,
 * clean-room reimplementation): a node/edge DAG with a shared typed state,
 * checkpoints, and interrupt-before/after points for human-in-the-loop.
 *
 * Unlike LangGraph, this engine uses plain Kotlin types and in-memory
 * checkpoints; it integrates with Aegis's real approval system for the
 * human-in-the-loop layer.
 */
class GraphAgentEngine<S : Any>(
    val entryNode: String,
    val transition: (nodeId: String, state: S) -> GraphNextStep<S>,
) {
    sealed interface GraphNextStep<S> {
        data class Goto<S>(val nextNodeId: String, val state: S) : GraphNextStep<S>
        data class End<S>(val state: S) : GraphNextStep<S>
    }

    data class GraphCheckpoint<S>(
        val nodeId: String,
        val state: S,
        val visited: List<String>,
        val interrupt: Boolean = false,
    )

    data class GraphExecutionTrace<S>(
        val checkpoints: List<GraphCheckpoint<S>>,
        val endState: S?,
        val interruptedAt: String? = null,
    )

    private val checkpoints = ConcurrentHashMap<String, MutableList<GraphCheckpoint<S>>>()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events

    /** Node whose interrupt point is skipped for the next run (used by resume). */
    private var skipInterruptAt: String? = null

    /** Interrupt points: nodes where execution halts for human approval. */
    var interruptBefore: Set<String> = emptySet()

    suspend fun run(
        initialState: S,
        maxSteps: Int = 50,
        interruptChecker: (suspend (nodeId: String) -> Boolean)? = null,
    ): GraphExecutionTrace<S> {
        val visited = mutableListOf<String>()
        val trace = mutableListOf<GraphCheckpoint<S>>()
        var current = entryNode
        var state = initialState
        var interruptedAt: String? = null

        repeat(maxSteps) {
            val shouldInterrupt = current in interruptBefore && current != skipInterruptAt ||
                (interruptChecker != null && interruptChecker(current))
            if (shouldInterrupt) {
                val cp = GraphCheckpoint(current, state, visited.toList(), interrupt = true)
                trace.add(cp)
                checkpoints.getOrPut("default") { mutableListOf() }.add(cp)
                interruptedAt = current
                _events.emit("interrupt:$current")
                return GraphExecutionTrace(trace, null, interruptedAt)
            }
            val step = transition(current, state)
            visited.add(current)
            trace.add(GraphCheckpoint(current, state, visited.toList()))
            _events.emit("node:$current")
            when (step) {
                is GraphNextStep.Goto -> { current = step.nextNodeId; state = step.state }
                is GraphNextStep.End -> {
                    checkpoints.getOrPut("default") { mutableListOf() }
                        .add(GraphCheckpoint(current, step.state, visited.toList()))
                    return GraphExecutionTrace(trace, step.state)
                }
            }
        }
        return GraphExecutionTrace(trace, state, interruptedAt = "max_steps")
    }

    /** Resume after an interrupt by supplying the approved state. */
    suspend fun resume(approvedState: S, interruptedNode: String): GraphExecutionTrace<S>? {
        val saved = checkpoints["default"]?.lastOrNull { it.nodeId == interruptedNode && it.interrupt }
            ?: return null
        // Re-run with the human-approved state, skipping the interrupt point
        // at the interrupted node once so execution can flow past it
        // (e.g., into an End step) without halting again.
        skipInterruptAt = interruptedNode
        return try {
            run(approvedState)
        } finally {
            skipInterruptAt = null
        }
    }

    /** Snapshot checkpoint storage for persistence. */
    fun persist(graphName: String, node: String, checkpoint: GraphCheckpoint<S>) {
        checkpoints.getOrPut(graphName) { mutableListOf() }.add(checkpoint)
    }

    fun checkpoints(graphName: String): List<GraphCheckpoint<S>> =
        checkpoints[graphName]?.toList() ?: emptyList()
}
