# Architecture — Aegis AI Agent OS

## 1. Dependency direction

Aegis follows a multi-module boundary:

```text
core + native abstractions
        ↑
provider / tool / feature implementations
        ↑
app composition root
```

Feature/provider/tool modules must not depend on `:app`. The application wires concrete implementations manually in `AegisApp`.

## 2. Main modules

```text
:app

:core:model          shared domain/events/errors/capabilities
:core:common         sanitization/common helpers
:core:database       Room persistence
:core:datastore      settings
:core:network        provider interface + safe HTTP
:core:security       Keystore credential vault/key pool
:core:permissions    Android permission helpers
:core:ui             Compose theme/design tokens
:core:agent          agent runtime/router/sub-agents/context
:core:tools          typed tool runtime + approval engine
:core:capabilities   dynamic capability registry
:core:memory         memory/RAG/vector abstractions
:core:workspace      workspace boundary
:core:sandbox        sandbox backend abstractions
:core:workflow       durable workflow engine

:native:runtime-rust isolated execution runtime
:native:local-llm    llama.cpp/GGUF/JNI local inference

:feature:chat
:feature:providers
:feature:device
:feature:browser
:feature:terminal
:feature:sandbox
:feature:files
:feature:security
:feature:settings
:feature:logs
:feature:schedules

:tool:android
:tool:filesystem
:tool:terminal
:tool:http
:tool:mcp
:tool:clipboard
:tool:ssh

:provider:openai
:provider:anthropic
:provider:google
:provider:openrouter
:provider:openai-compatible
:provider:local
```

## 3. Agent turn

```text
User message + bounded history
        ↓
AgentRuntime
        ↓
ContextManager.fit()
        ↓
AiProvider.generate(stream)
        ↓
GenerationEvent
   ├── text/usage/final result
   └── ToolCall
          ↓
       ToolRuntime
          ↓
       schema validation
          ↓
       typed Kotlin input
          ↓
       delegated scope policy
          ↓
       ApprovalEngine
          ↓
       capability/availability
          ↓
       bounded execution/retry
          ↓
       sanitized observation
          ↓
       correlated TOOL message
          └──────────────→ next model step
```

`ToolRuntime` is the single authorization/execution boundary. `AgentRuntime` mirrors observable state but does not independently authorize the same action.

Automatic replay after ambiguous failure is limited to SAFE/READ tools. Side-effecting tools are not automatically replayed.

## 4. Provider boundary

All providers implement the shared `AiProvider` contract and normalize streaming into provider-neutral `GenerationEvent` values. Chat history retains assistant tool-call metadata and correlated tool results so OpenAI-style, Anthropic-style, Gemini-style, and local routing layers can construct their native wire format without losing tool-call identity.

Network calls use asynchronous OkHttp callbacks with coroutine cancellation cancelling the underlying `Call`.

## 5. Android device automation

Accessibility automation follows:

```text
Observe → semantic select → execute once → observe → verify
```

Tree traversal is bounded by depth/node/text limits. Verification retries observation rather than replaying the action, preventing duplicate taps or other side effects when Android UI propagation is delayed.

## 6. Browser boundary

The embedded WebView backend keeps explicit tab ownership and bounded operations. URL policy rejects unsafe schemes/private navigation as configured, DOM selectors/text are bounded, and DOM snapshots are sanitized before they leave the backend. Form-control contents are not exported in snapshots.

Web content is untrusted and cannot change system policy or permissions.

## 7. Native runtime

### Rust execution runtime

```text
Kotlin client → Binder/AIDL → Android isolated process → JNI → Rust → child process
```

The runtime provides process-boundary hardening, program/environment allowlists, timeouts, cancellation, bounded output, and explicit file-descriptor working-directory access. It is not documented as a Linux container unless a separate backend provides that property.

### Local LLM

```text
LocalProvider → LocalModelBackend → JNI → C++ → pinned llama.cpp → GGUF
```

Local inference is limited to 64-bit ABIs in the current native module. Load preflight is responsible for rejecting invalid/unsafe model loads before memory pressure becomes an avoidable crash condition.

## 8. Workflow, scheduling and sub-agents

- `:core:workflow` stores and resumes durable workflow execution state.
- `:feature:schedules` maps schedule requests onto Android scheduling primitives and exposes exact/inexact constraints honestly.
- Sub-agents receive explicit provider/model, tool and capability scopes, budgets, timeout, parent id, and memory namespace. Authority can be reduced by delegation, not silently expanded.

## 9. Persistence and secrets

Room exports its schema and destructive fallback migration is disabled. Schema changes therefore require explicit migrations and tests.

Credential plaintext is not stored in Room/DataStore. `CredentialVault` encrypts secrets with independent Android Keystore-backed AES-GCM keys and stores encrypted blobs separately. Backup is disabled for application data.

## 10. Build and release

- AGP 8.5.2
- Gradle 8.7 distribution (SHA-256 pinned)
- Kotlin 1.9.24
- JDK/JVM target 17
- NDK 26.3.11579264
- Rust + cargo-ndk for native runtime
- CMake/NDK for llama.cpp JNI

The release definition is CI evidence, not source-tree claims: lint, unit/native tests, emulator instrumentation, security checks, release APK/AAB build, and signed artifact verification must pass on the exact release commit.
