#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
serial="${ANDROID_SERIAL:-emulator-5554}"
pkg=ru.focusguard.android.lab
if [[ "$serial" != emulator-* ]]; then echo 'This script only permits disposable emulator-* targets.' >&2; exit 1; fi
if [[ "$("$ADB" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then echo 'Not an emulator' >&2; exit 1; fi
"$ADB" -s "$serial" install -r -t android/app/build/outputs/apk/lab/debug/app-lab-debug.apk
"$ADB" -s "$serial" install -r -t android/app/build/outputs/apk/androidTest/lab/debug/app-lab-debug-androidTest.apk
"$ADB" -s "$serial" shell dpm set-device-owner "$pkg/ru.focusguard.android.AdminReceiver"
report="$(mktemp)"
trap 'rm -f "$report"' EXIT
"$ADB" -s "$serial" shell am instrument -w -r "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "$report"
rg -q '^OK \([0-9]+ tests?\)' "$report"
if rg -q 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$report"; then exit 1; fi
"$ADB" -s "$serial" shell dumpsys device_policy
