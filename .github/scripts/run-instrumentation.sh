#!/usr/bin/env bash
set -euo pipefail

for attempt in $(seq 1 60); do
  if adb shell service check package 2>/dev/null | grep -q 'found'; then
    exec ./gradlew --stacktrace connectedDebugAndroidTest
  fi
  sleep 2
done

echo 'Android package service did not become available before instrumentation.' >&2
adb shell service check package || true
exit 1

