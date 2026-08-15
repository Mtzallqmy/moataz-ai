#!/usr/bin/env bash
set -euo pipefail

mkdir -p .ci/android-diagnostics
collect_diagnostics() {
  adb logcat -d > .ci/android-diagnostics/logcat.txt 2>&1 || true
  adb shell getprop > .ci/android-diagnostics/getprop.txt 2>&1 || true
  adb shell dumpsys package > .ci/android-diagnostics/package-service.txt 2>&1 || true
  adb shell service check package > .ci/android-diagnostics/package-check.txt 2>&1 || true
}
trap collect_diagnostics EXIT

for attempt in $(seq 1 60); do
  if adb shell service check package 2>/dev/null | grep -q 'found'; then
    exec ./gradlew --stacktrace --no-parallel connectedDebugAndroidTest
  fi
  sleep 2
done

echo 'Android package service did not become available before instrumentation.' >&2
adb shell service check package || true
exit 1

