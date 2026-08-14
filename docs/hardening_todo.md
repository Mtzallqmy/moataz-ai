# Hardening TODO — from aegis_ai_agent_full_audit_hardening_prompt_ar.txt

> **ARCHIVED SNAPSHOT:** هذا الملف يوثق مرحلة أقدم من المشروع ولا يمثل الحالة الحالية. استخدم `README.md` و`docs/REPAIR_AND_VERIFICATION_REPORT_AR.md` ونتائج CI الحالية كمصدر للحالة الراهنة.


## Phase 1: Audit + build + CI stabilization
- [x] Read full prompt (1547 lines)
- [x] docs/current-audit.md — actual inspection of every subsystem
- [ ] CI green: remove -Plint=false hack, fix lint issues (or scope lint properly), build green

## Phase 2: Agent/Approval/Tool schema hardening
- [x] AgentRuntime: real tool calling verified, maxSteps enforced, token budget, execution timeout, cancellation, pause/resume, retries with typed errors
- [x] ApprovalEngine: PolicyDecision ALLOW/ASK/DENY, WAITING_FOR_APPROVAL suspend until user decides, approval request payload (tool id/name/target/args/risk/run id), options (once/for-run/always-matching/deny)
- [x] Tool schemas: JSON parse + schema validation + typed input + size limits (no `parseToolInput(arguments: String): Any = arguments`)
- [x] ToolRuntime: version, timeout, supportsCancellation, structured ToolResult (success/data/displayData/error/artifacts/metadata/duration)

## Phase 3: Provider + security hardening
- [x] CredentialVault: AES-GCM via Keystore, key lifecycle, rotation without breaking others, StrongBox detection (honest — only if verified)
- [x] Key Pool: Primary/Manual/Failover/RoundRobin/Weighted + enable/health/lastError/lastSuccess/rateLimit/test/masking
- [x] Providers: presets Groq/DeepSeek/xAI/Mistral/NVIDIA NIM/HF/Together/Fireworks/Cerebras/Ollama/LM Studio/llama.cpp-server (via openai-compatible)
- [ ] Provider capability discovery (streaming/tools/vision/reasoning/contextWindow...) + ProviderRuntime blocks unsupported capability
- [ ] Streaming normalization + UI batching/throttling

## Phase 4: Android device agent + verification
- [ ] AccessibilityService: semantic tree extraction (package/window/class/text/cd/resourceId/bounds/clickable/...) + snapshot versioning
- [ ] Selector engine: ByText/ByTextContains/ByDescription/ByResourceId/ByClass/ByRole/ByState/ByBounds/ByPackage + AND/OR/NOT/PARENT/CHILD/DESCENDANT/NEAR
- [ ] Device actions: click/longClick/tap/type/clear/scroll/swipe/back/home/open app/open URI + verification loop
- [x] Screenshot observation + vision fallback abstraction (versioned snapshots; vision is explicit external fallback, documented)

## Phase 5: Browser hardening
- [x] WebViewEngine: safe JS injection (JSON encoding, no string interpolation), safe browsing, URL validation, navigation policy, file:// restriction, intent policy, mixed content, downloads, cookies, minimal JS interface
- [ ] Browser APIs: open/navigate/snapshot/find/click/type/scroll/back/forward/reload/tabs/download/upload/evaluate/close
- [ ] Separate accessibility-based browser backend

## Phase 6: Terminal + Rust Sandbox
- [ ] TerminalBackend: AppProcess/Termux/Shizuku/Adb/Ssh/RemoteSandbox variants
- [ ] Terminal UI: tabs, stdin/out/err, ANSI, history, cwd, cancel/kill
- [ ] Rust native sandbox module (if feasible: cargo + gradle plugin) — process control, timeout, output limits, env allowlist; honest docs on isolation level
- [ ] Document if not feasible and why

## Phase 7: C++ Local LLM / llama.cpp (optional)
- [ ] NDK/CMake/JNI LocalModelBackend abstraction OR honest alternative (local server like llama.cpp-server via OpenAI-compatible). Decision needed.

## Phase 8: MCP + SSH + Memory + Skills
- [ ] MCP: spec-aligned (tools/resources/prompts), auth, health, reconnect, timeout, untrusted-response handling
- [ ] SSH: known_hosts, fingerprint verification, key/password, timeout, cancellation (JSch or sshj lib)
- [ ] Memory: Conversation/Working/LongTerm/Procedural/Workspace + search/scoring/expiry/namespaces/delete/pin, no secrets stored
- [ ] Embeddings abstraction: EmbeddingProvider/VectorStore/Retriever
- [ ] Skills: real registry, version, requiredCapabilities, risk metadata, no privilege escalation
- [ ] Context manager: token counting, summarization, compression, tool result reduction

## Phase 9: Workflows + Scheduling + Sub-agents
- [ ] Sub-agents: delegation, tool allowlists, budgets, memory namespaces, model selection, parent-child tracking
- [ ] Workflows: Trigger/Agent/Tool/Condition/Loop/Parallel/Delay/Transform/Approval/Notification/Output + persistence
- [ ] Scheduling: WorkManager real usage (exact/inexact/constrained) — honest docs
- [ ] Remote control: default OFF, localhost/LAN/pairing/cert/rate-limit (optional, documented)

## Phase 10: UI/UX + Design System + Motion
- [ ] core:designsystem: AegisTheme/Colors/Typography/Shapes/Spacing/Elevation/Motion/Icons/Components
- [ ] Screen-per-feature ViewModel + sealed ChatItem (UserMessage/AssistantMessage/ToolCallCard/ToolResultCard/ApprovalCard/ArtifactCard/ErrorCard/SystemEvent)
- [ ] Markdown, copy, regenerate, stop, provider/model selector
- [ ] App logo/launcher icon, dark/light, risk colors, status colors
- [ ] Motion: transitions, streaming indicators, approval transitions, skeletons

## Phase 11: Testing + profiling + security
- [ ] Unit: AgentRuntime scenarios, security (encryption/rotation/corrupted ciphertext/key invalidation), providers (streaming/malformed SSE/401/429/500)
- [ ] Integration: provider→agent→tool, approval, memory, workflow
- [ ] Security audit: exported components, intents, deep links, FileProvider, WebView JS bridge, clipboard, storage, backups (allowBackup + dataExtractionRules)
- [ ] Observability: RunID/agent/provider/model/tokens/tool calls/duration/approvals/errors/cost; no CoT; centralized redaction
- [ ] Baseline profiles; DB migrations + migration tests

## Phase 12: Production Release
- [ ] CI: format/lint/unit/assembleDebug/assembleRelease/bundleRelease green; no -Plint=false
- [ ] Release: debug APK + signed APK + AAB, version bump (65+), changelog, checksums
- [ ] GitHub release + docs/hardening report
