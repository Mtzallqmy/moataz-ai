# Contributing

## Setup

- JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`), Android SDK 34, Gradle 8.7.
- `./gradlew assembleDebug` builds the app; `./gradlew test` runs the local test suite.

## Module rules

1. `core/*` modules never depend on `feature/*`, `provider/*`, `tool/*`, or `app`.
2. `AiProvider` lives in `core:network`; providers depend only on `core:model` and `core:network`.
3. New providers must implement `AiProvider` and map their streaming format to `GenerationEvent`.
4. New tools must extend `AgentTool<T, R>`, declare an honest `RiskLevel`, and register with `ToolRuntime`.
5. Sensitive actions must route through `ApprovalEngine`.

## Code style

- 100% Kotlin, official style (`kotlin.code.style=official`).
- Compose + Material 3 for all UI; no XML layouts for new screens.
- Tests in `src/test` (JUnit 4 + kotlinx-coroutines-test). Local tests are mandatory for `core:common` and `core:agent` behavior.

## Commits

Use Conventional Commits (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`). One logical change per PR.

## Release

Release builds require signing env vars (`RELEASE_KEYSTORE`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). versionCode increments with every released build (currently 64).
