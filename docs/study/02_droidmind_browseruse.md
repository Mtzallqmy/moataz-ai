# DroidMind (hyperb1iss/droidmind)
- License: Apache-2.0 — يسمح
- مفاهيم: MCP server يعرض أدوات ADB (أجهزة/ملفات/تطبيقات/UI/shell) + command validation + risk assessment
- تنفيذنا: AdbDeviceBackend adapter (tool:android) — Kotlin Runtime.exec adb commands (ls, install, shell, screencap, dumpsys)
  - DeviceBackend interface: Accessibility / Shizuku(stub) / AdbDeviceBackend — ADB optional, app runs fine without it

# Browser-Use (browser-use/browser-use)
- License: MIT — يسمح
- مفاهيم: task-driven browser agent، verification after actions، DOM indexing، complex form automation، multi-step، BrowserBackend remote API
- تنفيذنا: BrowserBackend interface → EmbeddedWebView (WebViewEngine) / AccessibilityBrowser (tool:android) / BrowserUseRemote adapter (HTTP REST: POST /api/v4/runs)
  - BrowserUseRemote = optional external backend; never requires Playwright in APK
