# Moataz AI — Aegis Production Release

Moataz AI is a production-oriented Android AI agent framework organized as a modular Kotlin/Gradle project. It includes agent runtime, memory, workflow, security, browser, terminal, MCP, provider integrations, local-model support, and related tooling.

## Project status

This repository contains the Aegis production release snapshot. See the included architecture, security, audit, and contribution documents for implementation details and release notes.

## Structure

- `app/` — Android application shell and UI
- `core/` — agent runtime, memory, workflow, security, and common components
- `feature/` — browser, terminal, settings, schedules, security, and other features
- `provider/` — OpenAI, Anthropic, Google Gemini, OpenRouter, local, and OpenAI-compatible providers
- `tool/` — terminal, SSH, MCP, HTTP, filesystem, clipboard, and Android toolsets
- `native/` — Rust runtime and local LLM/JNI integration
- `scripts/` — release/static verification helpers
- `docs/` — technical documentation and audit material

## Build

Use the included Gradle wrapper:

```bash
./gradlew tasks
```

Refer to the module-specific documentation and `ARCHITECTURE.md` for more details.

## Security

Do not commit API keys, local credentials, keystores, or generated secrets. See `SECURITY.md` and the repository secret-scan workflow.
