# تقرير إصلاح وتجهيز نسخة Aegis المرفوعة

## النطاق

هذا التقرير خاص بالنسخة التي تم تعديلها مباشرة داخل الأرشيف المرفوع. الهدف كان إصلاح العيوب القابلة للإثبات من المصدر، تقوية حدود الأمان والتنفيذ، تنظيف ملفات Gradle/CI، وإضافة اختبارات وبوابات تحقق بدل زيادة Features عشوائية.

## إصلاحات منفذة

### Gradle والبنية

- إصلاح ملفات `build.gradle.kts` التي كان فيها `android {}`/`publishing {}` داخل `dependencies {}` نتيجة دمج غير سليم.
- نقل JUnit وcoroutines-test إلى test configurations بدل تحميلها كـproduction dependencies حيث لزم.
- إزالة سكربتات إصلاح محلية مرتبطة بمسارات جهاز مطور محدد.
- تثبيت SHA-256 لتوزيعة Gradle 8.7 في wrapper properties.
- إزالة bypass دائم للـlint وإبقاء Release/Debug/AAB ضمن CI gates.

### Agent/Tool runtime

- جعل `ToolRuntime` المالك الوحيد للـretry والـapproval حتى لا تتضاعف side effects.
- إصلاح ترتيب timeout/cancellation حتى لا يصنف timeout كـcancel.
- إصلاح off-by-one في retry count.
- منع automatic retry للأدوات MODIFY/COMMUNICATION/FINANCIAL/SYSTEM_SENSITIVE بعد فشل/timeout غامض.
- دعم استمرار محادثة tool-calling عبر `toolCallId` و`toolCalls` provider-neutrally.
- تمرير history الفعلي إلى النموذج، مع context fitting يحذف turns كاملة بدل فصل tool-call عن tool result.
- إضافة routing hint صريح لمنع race بين اختيار Provider من UI وقراءة الإعدادات.
- ضبط catalog lookup بمهلة وعدم إجراء preflight شبكي عند smart routing بدون model صريح.
- تعقيم ملخصات approval ونتائج الأدوات قبل وصولها إلى model/UI قدر الإمكان عبر `SecretSanitizer`.

### Android Accessibility

- version monotonic للـsnapshot.
- حدود depth/node/text/resource-id لمنع شجرة ضخمة أو غير منضبطة.
- إزالة `!!` من child traversal وإعادة تدوير nodes بأمان.
- أهم إصلاح: execute-once ثم retry للـobservation/verification فقط؛ لا يعاد tap/action تلقائيًا بسبب تأخر verification.

### Browser

- URL policy قبل open/navigate.
- حدود selectors/text/scripts.
- sanitization للـDOM snapshot وحد أقصى للحجم/nodes.
- إزالة محتوى form fields من snapshot (`[form-field]`).
- Safe Browsing API guard بحسب Android API.
- الاحتفاظ ببنية tabs/navigation/verification بدل استبدالها بنسخة Browser أضعف.

### Network / SSRF

- تقوية DNS/address rejection للـloopback/link-local/site-local/multicast ونطاقات IPv4 الخاصة بالحجز/benchmark/CGNAT.
- عدم حجب 172/8 بالكامل؛ الاعتماد على private range الصحيح.
- private-network opt-in صريح فقط للـbackends المحلية مثل Ollama/LM Studio/llama.cpp server.

### Providers

- تحويل streaming إلى OkHttp async calls قابلة للإلغاء بدل blocking execute داخل Flow.
- تصحيح wire field names/tool-call correlation/usage handling واستمرار tool results للـOpenAI/OpenAI-compatible.
- تصحيح Anthropic streaming/tool-use/tool-result continuation وAPI version المستخدمة في المصدر.
- Gemini يستخدم API-key header، ويدعم function calls/function responses مع capability flags أكثر تحفظًا.
- حفظ النص النهائي حتى لو Provider أعاد terminal finalText بدون deltas.

### Credential Vault

- إصلاح rotation/alias lifecycle حتى لا يجعل secret الجديد غير قابل للقراءة.
- alias identity مشتق من SHA-256 بدل prefix collision-prone.
- legacy compatibility مع aliases الأقدم.
- StrongBox detection من PackageManager مع API guard/fallback.
- حذف مفاتيح Keystore عند delete/clear، وتعامل أفضل مع ciphertext/metadata التالف.
- مزامنة عمليات ApiKeyPool الأساسية وصحة counters.

### Database

- `exportSchema=true`.
- إزالة destructive migration fallback.
- إعداد Room schema output directory.
- Room testing في androidTest configuration.

### UI

- إصلاح Compose state fallbacks التي كانت تنشئ state جديدًا أثناء recomposition.
- Chat runtime collectors أصبحت دورة حياة واحدة بدل collectors متراكمة مع كل send.
- history/provider/model/routing selection أصبحت مترابطة مع runtime.
- إضافة design tokens مشتركة في `:core:ui` (colors/spacing/elevation/motion/icons/shapes/typography) بدل إنشاء module جديد غير ضروري لهذه النسخة.
- تحسين تطبيق العربية/RTL من الإعدادات عبر Activity recreation/bootstrap locale.

### CI / Release

- Trivy dependency/misconfiguration gate.
- license gate.
- Gitleaks full-history workflow.
- Rust host tests.
- lint + unit + native local-LLM + Debug + Release + AAB.
- API 34 x86_64 `connectedDebugAndroidTest`.
- wrapper validation في build/emulator/signed-release jobs.
- signed tag artifacts فقط بعد نجاح gates السابقة.
- APK signature verification وAAB jarsigner verification قبل checksum/upload.

### اختبارات Android المضافة

- Rust path: Kotlin -> Binder -> isolatedProcess -> JNI -> Rust -> child process، مع timeout/cancel/output limits/concurrency/private-file isolation/reconnect.
- llama JNI: رفض missing/corrupt GGUF.
- WebView: منع file/content access وmixed content وunsafe file navigation.

## تحقق تم داخل بيئة التعديل

نجح التحقق الساكن من ملفات المصدر والبنية بعد التعديلات (merge markers، XML/TOML، Gradle block corruption، release bypasses، key material، wrapper metadata، native pinning).

## ما لم يمكن إثباته داخل هذه البيئة

لا توجد هنا Android SDK/NDK أو Cargo جاهزة، كما أن Gradle wrapper يحتاج تنزيل Gradle/dependencies والبيئة تمنع اتصال shell الخارجي. لذلك لم أدّعِ نجاح:

- `./gradlew lint/test/assemble*`
- CMake/llama.cpp native compilation
- Rust Android cross-build
- emulator/device instrumentation execution
- real-device GGUF performance/OOM matrix
- signed release using your real GitHub Secrets

تم تجهيز CI و`./scripts/verify_release.sh` لتكون هذه الاختبارات هي البوابة النهائية بعد رفع المشروع.

## ملاحظة Gradle wrapper

الـ`gradle-wrapper.jar` الموجود في الأرشيف هو bootstrap رسمي معروف من سلسلة Gradle أقدم، بينما properties تستهدف Gradle 8.7. تمت إضافة checksum لتوزيعة 8.7 وCI wrapper validation. يفضّل على جهاز متصل بالشبكة إعادة توليد wrapper بGradle 8.7 حتى تتطابق bootstrap jar والتوزيعة، ثم إعادة CI. لا تستبدل الـJAR من مصدر غير رسمي.

## قرار الإصدار

النسخة **جاهزة للرفع كـRelease Candidate محسّن**. لا تعتبرها Production Verified قبل أن تكون كل GitHub Actions المطلوبة خضراء على نفس commit، ثم تشغيل device matrix المطلوبة، ثم إنشاء tag وبناء signed artifacts من ذلك commit فقط.
