# تعليمات رفع النسخة المعدلة

هذه النسخة معدة للرفع كـ **Release Candidate**.

## قبل الدمج إلى main

1. ارفع محتويات هذا المجلد إلى فرع جديد أو إلى فرع الإصلاح الحالي.
2. لا تستخدم Force Push على `main`.
3. افتح Pull Request إلى `main`.
4. انتظر جميع GitHub Actions:
   - Secret Scan
   - Dependency/security scan
   - License check
   - Rust runtime tests
   - Android lint/tests/native/release builds
   - Android x86_64 instrumentation
5. إذا فشل أي Job، افتح Log وأصلح Root Cause؛ لا تعطل الـGate.
6. لا تنشئ Release tag قبل نجاح جميع الـGates.

## تحقق محلي سريع

```bash
python3 scripts/verify_static.py
```

## تحقق كامل على جهاز Android development مجهز

```bash
./scripts/verify_release.sh
./gradlew connectedDebugAndroidTest
```

## Gradle wrapper

الـwrapper properties تستهدف Gradle 8.7 وتحتوي SHA-256 للتوزيعة. يفضل بعد الرفع وعلى جهاز متصل بالشبكة إعادة توليد wrapper بGradle 8.7 لضمان تطابق bootstrap jar أيضًا، ثم commit للتغيير فقط إذا نجح wrapper validation وCI.

## التوقيع

لا تضع keystore أو كلمات مرور داخل المستودع. استخدم GitHub Secrets فقط كما هو موضح في `README.md` و`.github/workflows/build.yml`.
