# Aegis Workflow Engine

This module is a durable workflow runtime. It is deliberately separate from
`GraphAgentEngine`, which models agent reasoning flow rather than persisted
automation runs.

Supported node types are Trigger, Agent, Tool, Condition, Loop, Parallel, Delay,
Transform, Approval, Notification, and Output. Conditions/transforms are bounded
declarative JSON operations; arbitrary JavaScript or shell evaluation is not used.

The runtime validates graphs before persistence, enforces per-node retry/timeout,
executes parallel branches concurrently with a durable join, bounds loops, records
node outputs and a non-chain-of-thought timeline, and implements pause, resume,
cancellation, and startup recovery. `AtomicFileWorkflowStore` fsyncs a private app
file and atomically replaces it; corrupt persistence is surfaced rather than reset.

External Agent, Tool, Approval, and Notification effects require an injected
`WorkflowActionExecutor`; there is no no-op production executor. Execution is
at-least-once across crashes. Every external call receives a stable
`idempotencyKey` (`runId:tokenId`) and adapters must deduplicate with it. Claiming
exactly-once for arbitrary external systems would be incorrect.
