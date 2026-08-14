# AGENTS.md — conventions for AI assistants working on this repo

## Build

```bash
cd /home/ubuntu/CleanAgent
./gradlew assembleDebug            # or assembleRelease with signing env vars
./gradlew test                     # local JUnit tests (must stay green)
```

Memory-constrained CI box: use `-Dorg.gradle.jvmargs="-Xmx1800m"` and skip lint
(`-x lint -x lintVitalRelease -x lintVitalAnalyzeRelease`) if the daemon crashes.

## Hard rules

1. **100% Kotlin.** Never add Java, C/C++, Rust, NDK, or JNI code.
2. **No dependency-framework DI.** Wire new components manually in `AegisApp`; use registries for pluggability.
3. **Provider contract.** `AiProvider` (in `core:network`) exposes `generate(request: GenerationRequest): Flow<GenerationEvent>`, `listModels()`, `testConnection()`. New providers MUST stream normalized `GenerationEvent`s.
4. **Risk honesty.** Every new tool declares its true `RiskLevel`. MODIFY/COMMUNICATION/FINANCIAL/SYSTEM_SENSITIVE must pass through `ApprovalEngine`.
5. **Secrets never in code or configs.** Keys come from `CredentialVault` (Android Keystore) via lazy lambdas. Never commit secrets; sanitize anything logged.
6. **SSRF.** All outbound HTTP goes through `SafeHttpClient`; never create a raw `OkHttpClient` in feature modules.
7. **Tests.** Add JUnit tests for new core:common / core:agent logic. Keep `./gradlew test` passing.
8. **Strings.** New UI strings go in `values/strings.xml` and `values-ar/strings.xml`.
9. **Module boundaries.** Respect the dependency graph in ARCHITECTURE.md; adding a forbidden dependency is a build-time signal to fix the design.
10. **versionCode** in `app/build.gradle.kts` increments on each released build (currently 64).
