#!/usr/bin/env bash
set -euo pipefail

API_LEVEL="${1:-35}"
case "$API_LEVEL" in
  29|35) ;;
  *) echo "Unsupported test API level: $API_LEVEL" >&2; exit 2 ;;
esac

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must be set}"
: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"
GRADLE="$RUNNER_TEMP/gradle-8.9/bin/gradle"
RESULT_DIR="$GITHUB_WORKSPACE/eve-deep-results/api-$API_LEVEL"
GRADLE_LOG="$RESULT_DIR/gradle-test.log"
mkdir -p "$RESULT_DIR"

echo "EVE deep face test on Android API $API_LEVEL" | tee "$RESULT_DIR/environment.txt"
echo "Expected offline execution: app must not declare INTERNET; bundled ML models and data are local." | tee -a "$RESULT_DIR/environment.txt"

# Always collect diagnostics, including when ADB loses the emulator before tests start.
capture_diagnostics() {
  code=$?
  trap - EXIT
  {
    echo "Deep test exit code: $code"
    echo "--- adb devices ---"
    timeout 10s adb devices -l 2>&1 || true
    echo "--- emulator state ---"
    timeout 10s adb get-state 2>&1 || true
    echo "--- boot completed ---"
    timeout 10s adb shell getprop sys.boot_completed 2>&1 || true
    echo "--- recent logcat ---"
    timeout 15s adb logcat -d -v threadtime 2>&1 | tail -n 250 || true
  } > "$RESULT_DIR/diagnostics-on-exit.txt"
  exit "$code"
}
trap capture_diagnostics EXIT

MANIFEST="$GITHUB_WORKSPACE/eve-project/app/src/main/AndroidManifest.xml"
if grep -Eq 'android.permission.INTERNET' "$MANIFEST"; then
  echo "FAIL: app manifest requests INTERNET permission; offline-only invariant broken." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi

echo "--- Initial ADB devices ---" | tee -a "$RESULT_DIR/environment.txt"
timeout 15s adb devices -l 2>&1 | tee "$RESULT_DIR/adb-devices-before.txt" || true
adb start-server >> "$RESULT_DIR/environment.txt" 2>&1 || true

# Do not wait forever if the emulator booted slowly or adb's transport went stale.
if ! timeout 90s adb wait-for-device >> "$RESULT_DIR/environment.txt" 2>&1; then
  echo "WARN: ADB did not become ready on first attempt; restarting ADB transport." | tee -a "$RESULT_DIR/environment.txt"
  adb kill-server >> "$RESULT_DIR/environment.txt" 2>&1 || true
  sleep 2
  adb start-server >> "$RESULT_DIR/environment.txt" 2>&1 || true
  timeout 15s adb devices -l >> "$RESULT_DIR/environment.txt" 2>&1 || true
  if ! timeout 90s adb wait-for-device >> "$RESULT_DIR/environment.txt" 2>&1; then
    echo "FAIL: ADB could not reach the emulator after recovery. See diagnostics-on-exit.txt." | tee -a "$RESULT_DIR/environment.txt"
    exit 70
  fi
fi
if ! timeout 15s adb get-state 2>&1 | grep -q '^device# kill -0 can remain true for an unreaped zombie, leaving CI apparently stuck forever.
GRADLE_EXIT=0
timeout 900s "$GRADLE" --no-daemon --stacktrace -p "$GITHUB_WORKSPACE/eve-project" \
  testDebugUnitTest connectedDebugAndroidTest assembleDebug > "$GRADLE_LOG" 2>&1 || GRADLE_EXIT=$?
cat "$GRADLE_LOG"

timeout 15s adb logcat -d -v threadtime > "$RESULT_DIR/logcat.txt" 2>&1 || true
timeout 10s adb devices -l > "$RESULT_DIR/adb-devices-after.txt" 2>&1 || true
cp "$RUNNER_TEMP/face-fixture-sha256.txt" "$RESULT_DIR/face-fixture-sha256.txt" 2>/dev/null || true
grep -E "EVE_TEST|AndroidJUnitRunner|Tests run|OK \\(|FAILURES!!!|FATAL EXCEPTION|ANR in|BUILD SUCCESSFUL|BUILD FAILED" \
  "$RESULT_DIR/logcat.txt" "$GRADLE_LOG" > "$RESULT_DIR/test-signals.txt" || true

if [ "$GRADLE_EXIT" -ne 0 ]; then
  echo "FAIL: Gradle/unit/instrumentation tests exited with status $GRADLE_EXIT." | tee -a "$RESULT_DIR/environment.txt"
  exit "$GRADLE_EXIT"
fi

if ! grep -F "FACE_FIXTURE_PASS" "$RESULT_DIR/logcat.txt" "$GRADLE_LOG" >/dev/null 2>&1; then
  echo "FAIL: the real bundled face detector did not report FACE_FIXTURE_PASS." | tee -a "$RESULT_DIR/environment.txt"
  echo "Review logcat.txt, gradle-test.log, and the instrumentation XML reports." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi

if ! grep -F "FER_MODEL_PASS" "$RESULT_DIR/logcat.txt" "$GRADLE_LOG" >/dev/null 2>&1; then
  echo "FAIL: the bundled TensorFlow Lite facial-emotion model did not report FER_MODEL_PASS." | tee -a "$RESULT_DIR/environment.txt"
  echo "The integration test must load the model, predict an emotion from a detected face crop, and feed it into the emotional brain and eye generator." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi

if ! grep -F "OFFLINE_AFFECT_LEARNING_PASS" "$RESULT_DIR/logcat.txt" "$GRADLE_LOG" >/dev/null 2>&1; then
  echo "FAIL: offline preference-learning persistence was not confirmed by instrumentation." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi

if [ ! -f "$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk" ]; then
  echo "FAIL: expected debug APK was not generated." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi
sha256sum "$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk" | tee "$RESULT_DIR/apk-sha256.txt"
echo "PASS: ML Kit face detector, bundled TFLite facial-emotion model, affect decision, generated eye profile, offline preference memory, unit tests, and APK build all completed offline." | tee -a "$RESULT_DIR/environment.txt"
; then
  echo "FAIL: ADB reports no usable emulator device after wait-for-device." | tee -a "$RESULT_DIR/environment.txt"
  exit 71
fi
echo "PASS: Android emulator transport is ready." | tee -a "$RESULT_DIR/environment.txt"

# Disable Wi-Fi; the app manifest has no INTERNET permission, and both ML models are bundled.
timeout 10s adb shell svc wifi disable >> "$RESULT_DIR/environment.txt" 2>&1 || true
# Clearing logcat can fail on some API 29 emulator images. Diagnostic-only.
timeout 10s adb logcat -c >/dev/null 2>&1 || echo "WARN: could not clear emulator logcat buffer; tests will continue." | tee -a "$RESULT_DIR/environment.txt"

# Run Gradle directly and capture its exit code. Do not poll a background PID:
# kill -0 can remain true for an unreaped zombie, leaving CI apparently stuck forever.
GRADLE_EXIT=0
timeout 900s "$GRADLE" --no-daemon --stacktrace -p "$GITHUB_WORKSPACE/eve-project" \
  testDebugUnitTest connectedDebugAndroidTest assembleDebug > "$GRADLE_LOG" 2>&1 || GRADLE_EXIT=$?
cat "$GRADLE_LOG"

adb logcat -d -v threadtime > "$RESULT_DIR/logcat.txt" || true
cp "$RUNNER_TEMP/face-fixture-sha256.txt" "$RESULT_DIR/face-fixture-sha256.txt" 2>/dev/null || true
grep -E "EVE_TEST|AndroidJUnitRunner|Tests run|OK \\(|FAILURES!!!|FATAL EXCEPTION|ANR in|BUILD SUCCESSFUL|BUILD FAILED" \
  "$RESULT_DIR/logcat.txt" "$GRADLE_LOG" > "$RESULT_DIR/test-signals.txt" || true

if [ "$GRADLE_EXIT" -ne 0 ]; then
  echo "FAIL: Gradle/unit/instrumentation tests exited with status $GRADLE_EXIT." | tee -a "$RESULT_DIR/environment.txt"
  exit "$GRADLE_EXIT"
fi

if ! grep -F "FACE_FIXTURE_PASS" "$RESULT_DIR/logcat.txt" "$GRADLE_LOG" >/dev/null 2>&1; then
  echo "FAIL: the real bundled face detector did not report FACE_FIXTURE_PASS." | tee -a "$RESULT_DIR/environment.txt"
  echo "Review logcat.txt, gradle-test.log, and the instrumentation XML reports." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi

if ! grep -F "FER_MODEL_PASS" "$RESULT_DIR/logcat.txt" "$GRADLE_LOG" >/dev/null 2>&1; then
  echo "FAIL: the bundled TensorFlow Lite facial-emotion model did not report FER_MODEL_PASS." | tee -a "$RESULT_DIR/environment.txt"
  echo "The integration test must load the model, predict an emotion from a detected face crop, and feed it into the emotional brain and eye generator." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi

if ! grep -F "OFFLINE_AFFECT_LEARNING_PASS" "$RESULT_DIR/logcat.txt" "$GRADLE_LOG" >/dev/null 2>&1; then
  echo "FAIL: offline preference-learning persistence was not confirmed by instrumentation." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi

if [ ! -f "$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk" ]; then
  echo "FAIL: expected debug APK was not generated." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi
sha256sum "$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk" | tee "$RESULT_DIR/apk-sha256.txt"
echo "PASS: ML Kit face detector, bundled TFLite facial-emotion model, affect decision, generated eye profile, offline preference memory, unit tests, and APK build all completed offline." | tee -a "$RESULT_DIR/environment.txt"
