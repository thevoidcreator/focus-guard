#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../android"
: "${JAVA_HOME:?Install JDK 21 and set JAVA_HOME}"
: "${ANDROID_HOME:?Install SDK platform 36 and build tools 36.0.0}"
./gradlew --no-daemon :core:test :app:assembleLabDebug :app:assembleProductionRelease :app:lintProductionRelease :guardian:assembleDebug :guardian:lintDebug :app:lintLabDebug
mkdir -p ../dist/android
cp app/build/outputs/apk/lab/debug/app-lab-debug.apk ../dist/android/FocusGuard-0.2-phone-lab.apk
cp guardian/build/outputs/apk/debug/guardian-debug.apk ../dist/android/FocusGuard-0.2.1-guardian-android11.apk
cp app/build/outputs/apk/production/release/app-production-release-unsigned.apk ../dist/android/
sha256sum ../dist/android/*.apk > ../dist/android/SHA256SUMS
