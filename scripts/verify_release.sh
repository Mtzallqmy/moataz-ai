#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

python3 scripts/verify_static.py

if command -v cargo >/dev/null 2>&1; then
  cargo test --locked --manifest-path native/runtime-rust/rust/Cargo.toml
else
  echo "ERROR: cargo is required for the Rust verification gate." >&2
  exit 2
fi

if [[ ! -x ./gradlew ]]; then chmod +x ./gradlew; fi

./gradlew --stacktrace lint
./gradlew --stacktrace test
./gradlew --stacktrace :native:local-llm:testDebugUnitTest :native:local-llm:assembleDebug
./gradlew --stacktrace assembleDebug
./gradlew --stacktrace assembleRelease bundleRelease

echo "PASS: local release verification gates completed."
echo "Run connectedDebugAndroidTest on an API 34 x86_64 emulator/device before declaring the build release-ready."
