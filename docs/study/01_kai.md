# Kai 9000 (SimonSchubert/Kai) — دراسة
- License: Apache-2.0 (شعار في الصفحة + LICENSE.txt present) — يسمح بالمزج والتعديل مع حفظ الإشعار
- مفاهيم مفيدة:
  1. Linux Sandbox (proot + Alpine Linux userland ~3MB) — بدون root
  2. Terminal تفاعلي بجانب الـ AI
  3. Memory: hitCount — ترقية الذكريات المفيدة (>=5 ضربات) إلى system prompt
  4. Heartbeat: فحص ذاتي دوري كل 30 دقيقة (8am-10pm) — إذا يحتاج إجراء يُنبّه المستخدم
  5. Multi-service fallback (29 provider) مع failover تلقائي — لدينا Key Pool
  6. On-device inference عبر LiteRT (Android) — بديل llama.cpp للـ local
  7. MCP curated list مع auto-reconnect
- تنفيذنا المقترح (clean-room، Apache-2.0 يسمح):
  - ProotSandbox concept: ProotTerminalBackend adapter (feature:terminal) — تنفيذ نقي بالـ Kotlin Process
  - Memory hit counting + promotion to procedural/system prompt (MemoryStore + SkillRegistry/SysPrompt)
  - HeartbeatAgent (WorkManager periodic) — ملاحظة: WorkManager يُستخدم فعليًا الآن
  - LiteRT local model adapter (OpenAI-compatible style abstraction, no native dep)
