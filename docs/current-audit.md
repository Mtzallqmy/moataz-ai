# Current Audit — Aegis AI Agent OS (state before hardening)

> **ARCHIVED SNAPSHOT:** هذا الملف يوثق مرحلة أقدم من المشروع ولا يمثل الحالة الحالية. استخدم `README.md` و`docs/REPAIR_AND_VERIFICATION_REPORT_AR.md` ونتائج CI الحالية كمصدر للحالة الراهنة.


**Method:** direct inspection of source files in this repository (not README, not plans, not commit messages). All findings reference actual files and line counts.

**Snapshot:** 37 Gradle modules, 48 main Kotlin sources (~4,930 LOC), 25 passing JUnit tests, build green (debug + release, versionCode 64).

---

## 1. Agent Runtime — `core/agent/.../AgentRuntime.kt`

| Aspect | Status |
|---|---|
| Real tool-calling loop | Implemented — multi-step loop, collects streamed tool calls, executes via ToolRuntime |
| State machine | Implemented — IDLE/THINKING/PLANNING/WAITING_FOR_TOOL/EXECUTING_TOOL/WAITING_FOR_APPROVAL/OBSERVING/REPLANNING/PAUSED/COMPLETED/FAILED/CANCELLED |
| `maxSteps` enforced | Implemented — loop bound, but exceeding it still emits `COMPLETED` with text "Reached maximum steps." (honest but weak: should be FAILED/budget exceeded state semantics) |
| Token budget | **PARTIAL** — `maxTokensPerRun = 200_000` declared but NEVER enforced in the loop; only Usage event counters are accumulated |
| Execution timeout | Implemented — `withTimeout(executionTimeoutMs)` |
| Cancellation | Implemented — `cancel()` cancels the Job, mapped to CANCELLED |
| Pause/resume | **MISSING** — PAUSED state exists but nothing suspends/resumes into it |
| Retries | **MISSING** — failed steps do not retry with backoff |
| Provider errors typed | Implemented — ProviderError sealed; mapError exists |
| Tool errors typed | Implemented — ToolResultEnvelope with ToolErrorCategory (TIMEOUT/CANCELLED/APPROVAL_REQUIRED/CAPABILITY_UNAVAILABLE/RETRYABLE/GENERIC) |
| Run state persistence | **MISSING** — run record lives only in StateFlow; nothing writes to Room |
| Approval integration | **CRITICAL GAP** — line 168 logs "Tool approved: X" and executes directly. ApprovalEngine.decide() runs inside ToolRuntime (synchronous), but `ASK_EVERY_TIME` policy resolves to `ALLOW_ONCE` by default (line 127 fallback), and the runtime never suspends into WAITING_FOR_APPROVAL waiting for a user decision |
| Fake completion | Clean — no fake delays or hard-coded success |

## 2. Approval Engine — `core/tools/.../ApprovalEngine.kt` (lines 109–133)

| Aspect | Status |
|---|---|
| PolicyDecision ALLOW/ASK/DENY | **PARTIAL** — policy is evaluated but the decision resolver maps both ASK_ONCE and ASK_EVERY_TIME to ALLOW_ONCE by default; ASK never actually suspends |
| WAITING_FOR_APPROVAL suspend until user decides | **MISSING** — no async approval channel (no CompletableDeferred/request queue per run id) |
| Approval request payload | Implemented — ApprovalRequest carries id, toolName, action, target, argumentsSummary, riskLevel, requestingAgent, reason |
| User options | Implemented — ALLOW_ONCE/ALLOW_FOR_TASK/ALWAYS_ALLOW/DENY (enum), per-risk and per-rule allowlists |
| No execution before decision | **VIOLATED** — see AgentRuntime line 168; ToolRuntime blocks synchronously with a default ALLOW fallback |

## 3. Tool schemas — `core/model/.../Models.kt` + `AgentRuntime.parseToolInput`

- `parseToolInput(arguments: String): Any = arguments` in AgentRuntime line 193 — **production violation** (returns raw String as Any, no JSON parsing/validation).
- ToolDescriptor already has inputSchema/outputSchema (JSON string fields), riskLevel, timeoutMs, supportsCancellation — good contract.
- **Missing:** JSON parse → schema validate → typed input pipeline; size limits; field validation; security validation.

## 4. Tool Runtime — `core/tools/.../ToolRuntime.kt`

| Aspect | Status |
|---|---|
| Timed execution with cancellation | Implemented (`withTimeout(descriptor.timeoutMs)`) |
| Per-run tool-call budget | Implemented (mutex-guarded counter, maxToolCallsPerRun=50) |
| Capability gate | Implemented — checks CapabilityRegistry per requiredCapability |
| Availability check | Implemented — tool.availability(context) |
| Structured result | Implemented — ToolResultEnvelope(success/data/displayData/error/durationMs/isRetryable/errorCategory); **Missing: artifacts, metadata** |

## 5. Capability Registry — `core/capabilities/.../CapabilityRegistry.kt`

- Non-boolean states implemented: AVAILABLE/PERMISSION_REQUIRED/SERVICE_DISABLED/BACKEND_UNAVAILABLE/DEVICE_UNSUPPORTED/CONFIGURATION_REQUIRED/SECURITY_DENIED via `Capability.availability(): suspend`.
- **Missing:** DEGRADED state (7th enum value in directive); no built-in health checks for concrete backends (e.g. terminal.shizuku — no Shizuku backend exists yet); no registered capabilities in AegisApp (registry is empty after construction).

## 6. Credential Vault — `core/security/.../CredentialVault.kt`

| Aspect | Status |
|---|---|
| AES-256-GCM via Android Keystore | Implemented — authenticated encryption, random IV, 128-bit tag |
| Plaintext never persisted | Implemented — ciphertext only in MODE_PRIVATE SharedPreferences |
| Corrupted ciphertext | Implemented — GeneralSecurityException deletes the single ref |
| Key lifecycle | **PARTIAL** — single master key alias for ALL secrets; rotation (line 68) DELETES THE MASTER KEY → invalidates every other secret (rotation breaks others) |
| Master key design | **PARTIAL** — one global alias, no StrongBox detection (honest: none claimed), no corruption recovery beyond delete |
| Key pool | Implemented — ApiKeyPool with PRIMARY/FAILOVER/ROUND_ROBIN/WEIGHTED strategies, failover excludes on 5xx; **Missing: enable/disable/health/lastError/lastSuccess/rate-limit state/test/masking** |

## 7. Providers — `provider/{openai,anthropic,google,openrouter,openai-compatible}`

- Five real providers with SSE streaming → normalized GenerationEvent flow. Verified real HTTP/SSE parsing code (no fake responses).
- ModelCapabilities model exists (streaming/toolCalling/parallelToolCalling/vision/reasoning/jsonMode/structuredOutput/embeddings/imageGeneration/responsesApi/contextWindow/maxOutputTokens) — **but no provider populates it** (all defaults) and no ProviderRuntime checks it.
- **Missing presets:** Groq, DeepSeek, xAI, Mistral, NVIDIA NIM, HuggingFace, Together, Fireworks, Cerebras, Ollama, LM Studio, llama.cpp-server (would reuse openai-compatible).
- **Missing:** 401/429/500 typed mapping coverage audit (AuthenticationError/RateLimitError exist but mapping depth per provider not fully verified); malformed SSE resilience (single provider parse loop, needs per-provider inspection).

## 8. Android Device Agent — `tool/android/AccessibilityAgentService.kt` + `SelectorEngine.kt`

- Semantic tree extraction: implemented — package/window/class/text/contentDescription/resourceId/bounds/clickable via UiNode.
- Snapshot versioning: **MISSING** (no version id on snapshots).
- Selectors: ByText/ByTextContains/ByContentDescription/ByResourceId/ByClass/ByRole/ByState/ByBounds/ByPackage + And/Or/Not/Child/Descendant/Near — **ALL IMPLEMENTED** (SelectorEngine.kt lines 32–47, find/matches/atCoordinates).
- Actions: tapAt/longPressAt/clickNode/typeInto/clearText/scrollUp/scrollDown/swipe/back/home/openApp — implemented; **MISSING: open URI, scrollUp actually calls ACTION_SCROLL_FORWARD twice (copy bug), verification loop exists (VerificationLoop class, lines 117+)**.
- Screenshot observation: **MISSING** (no screenshot capture API); vision fallback: **MISSING**.

## 9. Browser — `feature/browser/.../WebViewEngine.kt`

| Aspect | Status |
|---|---|
| JS bridge | Implemented with scoped `AegisBridge` + SnapshotBridge class — **CRITICAL ISSUE**: `clickSelector` uses raw string interpolation `'$selector'` inside evaluateJavascript — injection-prone; snapshot bridge uses replace hacks — must JSON-encode |
| Safe Browsing | **MISSING** (no enableSafeBrowsingAwareness) |
| URL validation / navigation policy | **MISSING** (shouldOverrideUrlLoading returns false — any URL loads; file://, intent:// not blocked) |
| Mixed content | **MISSING** |
| Downloads / cookies / cache policy | Partial — download interception exists; cookies/cache not restricted |
| APIs | navigate/click/type/snapshot exist; **MISSING:** find, scroll, back/forward, reload, tabs, download-as-tool, upload, evaluate (with safe encoding), close |
| Accessibility-based browser backend | **MISSING** (single WebView backend only) |

## 10. Terminal — `tool/terminal/TerminalToolSet.kt`

- Implemented: create/exec/kill sessions, real ProcessBuilder execution, timeout + maxOutputChars caps, tokenization (no raw shell), history of sessions.
- **Missing:** TermuxBackend, ShizukuBackend, AdbBackend, SshBackend delegation (TerminalBackend abstraction), interactive stdin, ANSI parsing, cwd tracking, Terminal UI (tabs/screens).

## 11. Sandbox — `feature/sandbox/SandboxManager.kt`

- Implemented: real AppOps inspection, sensitive-ops list.
- **Missing:** Rust/native sandbox runtime; execution boundaries (file boundary, env allowlist, output limits, resource constraints); capability-based mounts; secret separation (documented but no mount enforcement).

## 12. MCP — `tool/mcp/McpClient.kt`

- Implemented: JSON-RPC 2.0 Streamable HTTP per MCP 2025-03-26 spec; initialize; tools/list; tools/call; timeout; secrets not logged; responses treated as JsonObject (untrusted-safe parsing).
- **Missing:** resources/list, prompts/list, OAuth/auth support, server health check API, reconnect, tool permissions upstream wiring into ToolRuntime (calls are currently direct via McpTool adapter — approval path depends on ToolRuntime wiring which exists).

## 13. SSH — `tool/ssh/SshToolSet.kt`

- Implemented: RealProcessSshBackend (delegates to system `ssh`), host-key policy ACCEPT_NEW/STRICT (StrictHostKeyChecking=no banned), typed connection spec validation, timeout.
- **Missing:** known_hosts management (delegates to user's ~/.ssh — acceptable but not managed), fingerprint verification API, password auth path, passphrase handling, PTY, streaming output, cancellation (process-level kill exists in TerminalToolSet; SshExecTool cancellation needs verification).

## 14. Memory — `core/memory/MemoryStore.kt`

- Implemented: put/edit/delete/pin/list(namespace)/search with Room DAO.
- **Missing:** MemoryEntity schema has namespace + value; **no scoring, no expiry/TTL, no conversation/working/procedural/workspace memory type distinctions, no secret scan on put** (SecretSanitizer exists but not called here).

## 15. Skills — `core/agent/Skills.kt`

- Implemented: SkillDescriptor (metadata, instructions), SkillLoader (markdown front-matter parser), SkillValidator (schema validation), SkillRegistry (load/get/all/remove).
- **Missing:** version field, requiredCapabilities field, risk metadata, privilege-escalation prevention enforcement, skill-tool integration (skills not injected into AgentRuntime tool lists).

## 16. Context Manager — `core/agent/ContextManager.kt`

- Implemented: context compaction. **Missing verified:** token counting, summarization, tool result reduction, UI tree reduction, screenshot selection, memory retrieval integration — needs inspection (small file).

## 17. Workflows / Scheduling / Sub-agents / Remote Control

- **ALL MISSING** — no workflow engine, no schedule store, no WorkManager usage anywhere in `src/main` (grep: 0 hits), no sub-agent delegation, no remote control surface (correctly absent — default OFF is the secure state; documenting as intentionally missing).

## 18. UI — app module + feature modules

- Implemented: Compose screens (ChatScreen, FeatureScreens, SettingsScreens), NavHost tabs, Material 3 theme, teal brand.
- ChatViewModel implemented (send/stop/resend/editMessage, observes AgentRuntime events).
- **Missing:** sealed ChatItem model (UserMessage/AssistantMessage/ToolCallCard/ToolResultCard/ApprovalCard/ArtifactCard/ErrorCard/SystemEvent) — ChatScreen uses raw events; Markdown rendering (basic); per-feature ViewModels (several features share generic FeatureScreens); design system module `core:designsystem` does not exist (core:ui exists with AppTheme); launcher icon is adaptive (res/BW.xml); dark/light variants partial; no motion tokens.

## 19. Database / persistence

- Room with KSP: AppDatabase, AgentRunDao, ChatMessageDao, MemoryDao (entity exists).
- **Missing:** schema version migrations + migration tests (exportSchema not verified), Room writes for runs (AgentRun never persisted).

## 20. Tests

- 25 JUnit tests: SecretSanitizerTest (7), ModelsTest (8), ProviderRegistryTest (10). **Missing:** AgentRuntime scenarios, security vault tests (mock keystore exists — good testability), provider streaming/malformed SSE/401/429/500, tool schema validation, approval suspend flow, integration tests, Android instrumented tests.

## 21. CI / Release

- `.github/workflows/build.yml`: build + test on push/PR; uploads debug APK. **Violations vs directive:** uses `-Plint=false` and no lint step (final path must not escape lint); no bundleRelease; no checksums/changelog on release.
- Release v1.0.0 (APK) exists. **Missing:** AAB, signed-release-on-tag, changelog, checksums, version 65+.

## 22. Security surface (manifest-level)

- `android:allowBackup="false"` + dataExtractionRules excludes sharedpref/database from cloud backup and device transfer — GOOD.
- MainActivity exported (required), accessibility service exported=false — GOOD.
- **Missing review items:** deep link/intent validation (no intent filters beyond launcher), clipboard exposure (ClipboardToolSet — agent can read/write clipboard; policy exists in approval but runtime does not apply for clipboard tools), log redaction (SecretSanitizer exists — verify all logging paths use it), no CoT stored (system prompt instructs internal CoT — good).

## 23. Native components

- **None.** 100% Kotlin. No Rust module, no C++/NDK module. The single `.so` in APK (`libdatastore_shared_counter.so`) is Google's DataStore dependency, not authored code.

---

## Priority Fix List (derived from audit)

1. **CRITICAL** — Approval flow: runtime must suspend into WAITING_FOR_APPROVAL until user decides; ApprovalEngine ASK must not default to ALLOW.
2. **CRITICAL** — parseToolInput: add JSON parsing + schema validation + typed input (remove `Any = arguments`).
3. HIGH — Enforce maxTokensPerRun in the loop; fix maxSteps-exceeded final state.
4. HIGH — Vault: per-secret key derivation or per-scope master keys so rotation of one secret never destroys others; add key rotation without master-key deletion.
5. HIGH — WebView: JSON-encode JS injection, URL validation + file:// block, safe browsing, mixed content, full API surface.
6. MEDIUM — Provider capability discovery + ProviderRuntime capability gate + presets.
7. MEDIUM — Persist runs to Room; migration setup.
8. MEDIUM — SelectorEngine scrollUp bug; screenshot observation abstraction.
9. MEDIUM — Memory: TTL/expiry, secret scan, type distinctions.
10. MEDIUM — Skills: version/capabilities/risk metadata + registry integration.
11. MEDIUM — KeyPool: health/lastError/lastSuccess/enable/masking/test.
12. LOW — Design system module, sealed ChatItem, per-feature ViewModels, motion tokens.
13. LOW — Rust sandbox: evaluate feasibility; if not feasible, document honestly and deliver Kotlin boundary enforcement.
14. LOW — WorkManager scheduling: real usage (exact/inexact honest docs).
15. CI: lint re-enabled properly, bundleRelease, checksums, changelog, versionCode 65.
