# Security Model — Aegis AI Agent OS

## 1. Credentials

- API keys are entered by the user at runtime (Settings → Providers) and stored exclusively in the **Android Keystore** via `core:security:CredentialVault`. They are never written to `SharedPreferences`, DataStore plaintext, logs, or crash reports.
- Providers receive a lazy `suspend () -> String?` lambda that resolves the key from the vault at call time — keys never live in provider object fields beyond the request.
- The app itself contains no hardcoded secrets.

## 2. Secret sanitization

`core:common:SecretSanitizer` masks OpenAI-style keys (`sk-...`), GitHub tokens (`ghp_...`, `github_pat_...`), Anthropic keys (`sk-ant-...`), and JWT tokens before any string can reach logs, telemetry, or tool output. Unit tests assert every pattern is masked.

## 3. SSRF protection

`core:network:SafeHttpClient` wraps OkHttp with an allow/deny host policy: loopback and link-local addresses are blocked by default unless explicitly enabled, so agent tools cannot be abused to scan the device's network or hit internal services.

## 4. Human-in-the-loop approval

Every tool declares a `RiskLevel` (SAFE → SYSTEM_SENSITIVE). `core:tools:ApprovalEngine` enforces the user's `ApprovalPolicy` (ALLOW / ASK_ONCE / ASK_EVERY_TIME / DENY). Any MODIFY, COMMUNICATION, FINANCIAL, or SYSTEM_SENSITIVE action surfaces an approval UI before execution. The agent runtime halts in `WAITING_FOR_APPROVAL` until the user decides.

## 5. Least privilege

- Dangerous actions require scoped permission requests (`core:permissions`).
- The Accessibility Service is opt-in and limited to UI automation tasks the user assigns.
- The sandbox policy (`feature:sandbox`) constrains where tools may read/write.

## 6. What the app does NOT do

- No network access without an explicit provider configuration by the user.
- No NDK/JNI/native code — the entire app is pure Kotlin, which reduces the native attack surface to zero.
- No bundled credentials, no tracking SDKs, no crash reporters that upload data.

## Reporting a vulnerability

If you discover a security issue, open a GitHub issue on https://github.com/Mtzallqmy/Ai marked `security`. Do not include real secrets in any report — sanitize them with the patterns described above.
