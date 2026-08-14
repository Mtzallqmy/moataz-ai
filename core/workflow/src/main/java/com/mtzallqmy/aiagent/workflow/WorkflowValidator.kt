package com.mtzallqmy.aiagent.workflow

class WorkflowValidationException(val violations: List<String>) : IllegalArgumentException(
    violations.joinToString(prefix = "Invalid workflow: ", separator = "; "),
)

class WorkflowValidator {
    fun validate(definition: WorkflowDefinition) {
        val errors = mutableListOf<String>()
        if (!ID_PATTERN.matches(definition.id)) errors += "workflow id is invalid"
        if (definition.version < 1) errors += "version must be positive"
        if (definition.nodes.isEmpty()) errors += "workflow has no nodes"
        if (definition.nodes.size > MAX_NODES) errors += "workflow exceeds $MAX_NODES nodes"
        if (definition.edges.size > MAX_EDGES) errors += "workflow exceeds $MAX_EDGES edges"
        if (definition.name.length > MAX_TEXT_LENGTH) errors += "workflow name is too long"

        val nodes = definition.nodes.associateBy { it.id }
        if (nodes.size != definition.nodes.size) errors += "node ids must be unique"
        if (nodes[definition.entryNodeId] !is TriggerNode) errors += "entry node must exist and be a Trigger node"

        definition.nodes.forEach { node ->
            if (!ID_PATTERN.matches(node.id)) errors += "invalid node id: ${node.id}"
            if (node.timeoutMillis !in 1..MAX_NODE_TIMEOUT_MILLIS) errors += "node ${node.id} timeout is out of bounds"
            when (node) {
                is DelayNode -> if (node.delayMillis < 0) errors += "delay ${node.id} cannot be negative"
                is LoopNode -> if (node.maxIterations !in 1..MAX_LOOP_ITERATIONS) {
                    errors += "loop ${node.id} maxIterations must be 1..$MAX_LOOP_ITERATIONS"
                }
                is AgentNode -> {
                    if (node.agentId.isBlank()) errors += "agent ${node.id} has no agentId"
                    if (node.prompt.isBlank()) errors += "agent ${node.id} has no prompt"
                    if (node.prompt.length > MAX_TEXT_LENGTH) errors += "agent ${node.id} prompt is too long"
                }
                is ToolNode -> if (node.toolId.isBlank()) errors += "tool ${node.id} has no toolId"
                is ApprovalNode -> if (node.title.isBlank() || node.summary.isBlank()) errors += "approval ${node.id} is incomplete"
                is NotificationNode -> if (node.channel.isBlank() || node.message.isBlank()) errors += "notification ${node.id} is incomplete"
                else -> Unit
            }
        }

        val duplicateEdges = definition.edges.groupBy { Triple(it.from, it.to, it.label) }.filterValues { it.size > 1 }
        if (duplicateEdges.isNotEmpty()) errors += "duplicate edges are not allowed"
        definition.edges.forEach { edge ->
            if (edge.from !in nodes) errors += "edge source does not exist: ${edge.from}"
            if (edge.to !in nodes) errors += "edge target does not exist: ${edge.to}"
            if (edge.label.isBlank()) errors += "edge ${edge.from}->${edge.to} has a blank label"
        }

        definition.nodes.forEach { node ->
            val outgoing = definition.edges.filter { it.from == node.id }
            when (node) {
                is OutputNode -> if (outgoing.isNotEmpty()) errors += "output ${node.id} cannot have outgoing edges"
                is ConditionNode -> requireLabels(node.id, outgoing, setOf("true", "false"), errors)
                is LoopNode -> requireLabels(node.id, outgoing, setOf("body", "done"), errors)
                is ParallelNode -> {
                    val branches = outgoing.filter { it.label == "branch" }
                    val done = outgoing.filter { it.label == "done" }
                    if (branches.size < 2 || branches.map { it.to }.distinct().size != branches.size) {
                        errors += "parallel ${node.id} requires at least two distinct branch edges"
                    }
                    if (branches.size > MAX_PARALLEL_BRANCHES) errors += "parallel ${node.id} exceeds $MAX_PARALLEL_BRANCHES branches"
                    if (done.size != 1 || outgoing.size != branches.size + 1) {
                        errors += "parallel ${node.id} requires exactly one done edge and only branch/done labels"
                    }
                }
                else -> if (outgoing.size != 1 || outgoing.singleOrNull()?.label != "next") {
                    errors += "node ${node.id} requires exactly one next edge"
                }
            }
        }

        val reachable = reachableFrom(definition.entryNodeId, definition.edges)
        val unreachable = nodes.keys - reachable
        if (unreachable.isNotEmpty()) errors += "unreachable nodes: ${unreachable.sorted().joinToString()}"
        if (definition.nodes.none { it is OutputNode && it.id in reachable }) errors += "workflow has no reachable Output node"
        validateCycles(definition, nodes, errors)
        validateValueReferences(definition, nodes, errors)

        if (errors.isNotEmpty()) throw WorkflowValidationException(errors.distinct())
    }

    private fun requireLabels(
        nodeId: String,
        outgoing: List<WorkflowEdge>,
        labels: Set<String>,
        errors: MutableList<String>,
    ) {
        if (outgoing.map { it.label }.toSet() != labels || outgoing.size != labels.size) {
            errors += "node $nodeId requires exactly ${labels.sorted().joinToString()} edges"
        }
    }

    private fun reachableFrom(entry: String, edges: List<WorkflowEdge>): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(entry) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            edges.filter { it.from == current }.forEach { queue.add(it.to) }
        }
        return visited
    }

    private fun validateCycles(
        definition: WorkflowDefinition,
        nodes: Map<String, WorkflowNode>,
        errors: MutableList<String>,
    ) {
        val visits = mutableMapOf<String, Visit>()
        val outgoing = definition.edges.groupBy { it.from }
        fun visit(id: String) {
            visits[id] = Visit.ACTIVE
            outgoing[id].orEmpty().forEach { edge ->
                when (visits[edge.to]) {
                    Visit.ACTIVE -> if (nodes[edge.to] !is LoopNode && nodes[edge.to] !is ParallelNode) {
                        errors += "cycle must return through Loop or Parallel node: ${edge.from}->${edge.to}"
                    }
                    Visit.DONE -> Unit
                    null -> visit(edge.to)
                }
            }
            visits[id] = Visit.DONE
        }
        if (definition.entryNodeId in nodes) visit(definition.entryNodeId)
    }

    private fun validateValueReferences(
        definition: WorkflowDefinition,
        nodes: Map<String, WorkflowNode>,
        errors: MutableList<String>,
    ) {
        fun check(value: WorkflowValue, owner: String) {
            if (value is WorkflowValue.NodeOutput && value.nodeId !in nodes) {
                errors += "$owner references missing output node ${value.nodeId}"
            }
        }
        definition.nodes.forEach { node ->
            when (node) {
                is ConditionNode -> { check(node.predicate.left, node.id); node.predicate.right?.let { check(it, node.id) } }
                is LoopNode -> { check(node.predicate.left, node.id); node.predicate.right?.let { check(it, node.id) } }
                is TransformNode -> node.assignments.values.forEach { check(it, node.id) }
                is OutputNode -> check(node.value, node.id)
                else -> Unit
            }
        }
    }

    private companion object {
        val ID_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.-]{0,127}$")
        const val MAX_LOOP_ITERATIONS = 10_000
        const val MAX_NODES = 1_000
        const val MAX_EDGES = 5_000
        const val MAX_PARALLEL_BRANCHES = 64
        const val MAX_TEXT_LENGTH = 64_000
        const val MAX_NODE_TIMEOUT_MILLIS = 7L * 24 * 60 * 60_000
    }

    private enum class Visit { ACTIVE, DONE }
}
