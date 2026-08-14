# Aegis AI Agent OS

Aegis is a multi-module Android AI-agent platform that combines provider-neutral model access, tool execution with policy/approval gates, Android accessibility automation, browser/terminal/SSH/MCP integrations, durable workflows and scheduling, a Rust isolated execution runtime, and optional on-device GGUF inference through llama.cpp/JNI.

> **Release status:** this source tree is hardened as a release candidate, but a release is considered verified only after the repository CI, Android emulator/device tests, native builds, and signed artifact checks are green for the exact commit being released.

- Package: `com.mtzallqmy.aiagent`
- Android: minSdk 26, compile/target SDK 34
- App version in this snapshot: `1.1.0` (`versionCode 65`)
- Android/Gradle toolchain: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7, JDK 17
- Native: Rust execution runtime + C/C++ llama.cpp bridge through Android NDK/JNI
- UI: Jetpack Compose + Material 3, Arabic/English resources, RTL support

## Main capabilities

- Provider-neutral streaming and tool calling: OpenAI, Anthropic, Gemini, OpenRouter, OpenAI-compatible endpoints, and local llama.cpp provider.
- OpenAI-compatible presets for common cloud and local endpoints (including Ollama, LM Studio, and llama.cpp server).
- Agent state machine with budgets, cancellation, pause/resume, structured tool-call continuation, conversation history, context fitting, and public execution timeline.
- Typed tool boundary: JSON schema validation -> Kotlin serialization -> typed tool input.
- Single tool authorization path through `ToolRuntime`: policy -> approval -> capability -> execution.
- Sensitive-action retry safety: automatic replay is restricted to SAFE/READ tools.
- Android accessibility automation with bounded/versioned snapshots and execute-once verification.
- Hardened WebView browser with URL policy, safe selector/text bounds, safe browsing, and sanitized DOM snapshots.
- Credential vault backed by Android Keystore/AES-GCM; application data is excluded from backup.
- SSRF-aware HTTP client with private-network access disabled unless a backend explicitly opts in.
- Rust native runtime in an Android isolated process with allowlists, timeouts, cancellation, and output limits.
- Local GGUF inference abstraction and llama.cpp/JNI native implementation (64-bit Android ABIs for local inference).
- Durable workflow engine, Android scheduling layer, scoped sub-agents, MCP, SSH, memory/RAG, sandbox and PRoot integration.

## Module layout

This snapshot contains 42 Gradle modules:

- `:app`
- `:core:*` — model/common/database/datastore/network/security/permissions/ui/agent/tools/capabilities/memory/workspace/sandbox/workflow
- `:native:runtime-rust`, `:native:local-llm`
- `:feature:*` — chat/providers/device/browser/terminal/sandbox/files/security/settings/logs/schedules
- `:tool:*` — android/filesystem/terminal/http/mcp/clipboard/ssh
- `:provider:*` — openai/anthropic/google/openrouter/openai-compatible/local

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for boundaries and data flow.

## Build prerequisites

A complete build needs:

1. JDK 17
2. Android SDK 34
3. Android NDK `26.3.11579264`
4. Rust toolchain with Android targets
5. `cargo-ndk` 4.1.2
6. Network access for dependency resolution and the pinned llama.cpp `FetchContent` dependency

The Gradle distribution is SHA-256 pinned in `gradle-wrapper.properties`.

## Verification

Fast repository sanity check (no Android SDK required):

```bash
python3 scripts/verify_static.py
```

Full local build gates on a configured Android/Rust machine:

```bash
./scripts/verify_release.sh
```

Android runtime/instrumentation verification:

```bash
./gradlew connectedDebugAndroidTest
```

CI also runs secret scanning, dependency/misconfiguration scanning, license checks, Rust host tests, lint, unit tests, native local-LLM build checks, Debug/Release/AAB builds, and API-34 x86_64 instrumentation. Signed tag builds verify APK/AAB signatures and produce SHA-256 checksums.

## Signing

Do not commit keystores or passwords. Signed tag builds expect these GitHub Secrets:

- `RELEASE_KEYSTORE_B64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

## Local model notes

`native/local-llm` pins llama.cpp to an immutable upstream commit in CMake. Real-device GGUF performance and memory limits depend on the model, quantization, context size, and device RAM; treat device-matrix evidence as a release gate rather than assuming emulator results generalize to production phones.

## Security notes

External webpages, files, terminal output, MCP payloads, and device UI content are treated as untrusted data. Secrets should enter through the credential vault or explicit user-controlled channels, not source files, logs, prompts, memory, or release artifacts.

## License

Private — all rights reserved (Mtzallqmy).
