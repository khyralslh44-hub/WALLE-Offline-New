#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must be set}"
: "${ANDROID_HOME:?ANDROID_HOME must be set}"
RESULT_DIR="$GITHUB_WORKSPACE/eve-llm-results"
APP_APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
mkdir -p "$RESULT_DIR"

echo "EVE real local language-model verification" | tee "$RESULT_DIR/environment.txt"
echo "Expected: one real Qwen generation, network disable, cold engine reload from app-private weights, a second real generation." | tee -a "$RESULT_DIR/environment.txt"

for APK in "$APP_APK" "$TEST_APK"; do
  if [ ! -s "$APK" ]; then
    echo "FAIL: missing built APK $APK" | tee -a "$RESULT_DIR/environment.txt"
    exit 2
  fi
done

# The first model setup intentionally needs the emulator's unmetered Internet connection.
timeout 15s adb wait-for-device
timeout 20s adb shell settings put global airplane_mode_on 0 >/dev/null 2>&1 || true
timeout 20s adb shell svc wifi enable >/dev/null 2>&1 || true
timeout 20s adb shell svc data enable >/dev/null 2>&1 || true
timeout 20s adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1 || true
sleep 8

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
AAPT="$(find "$SDK_ROOT/build-tools" -type f -name aapt -print 2>/dev/null | sort -V | tail -n 1)"
if [ -z "$AAPT" ]; then
  echo "FAIL: aapt not found." | tee -a "$RESULT_DIR/environment.txt"
  exit 3
fi
TEST_PACKAGE="$("$AAPT" dump badging "$TEST_APK" | sed -n "s/^package: name='\\([^']*\\)'.*/\\1/p" | head -n 1)"
if [ -z "$TEST_PACKAGE" ]; then
  echo "FAIL: could not read instrumentation package." | tee -a "$RESULT_DIR/environment.txt"
  exit 4
fi
echo "Instrumentation package: $TEST_PACKAGE" | tee -a "$RESULT_DIR/environment.txt"

timeout 240s adb install -r "$APP_APK" > "$RESULT_DIR/install-app.txt" 2>&1
cat "$RESULT_DIR/install-app.txt"
timeout 240s adb install -r "$TEST_APK" > "$RESULT_DIR/install-test.txt" 2>&1
cat "$RESULT_DIR/install-test.txt"
timeout 15s adb logcat -c >/dev/null 2>&1 || true
echo "Starting dedicated LocalLanguageBrainIntegrationTest. The initial 647 MB model download and CPU initialization may take several minutes." | tee -a "$RESULT_DIR/environment.txt"

INSTRUMENTATION_EXIT=0
timeout 3300s adb shell am instrument -w -r \
  -e class com.eve.robot.LocalLanguageBrainIntegrationTest \
  "$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner" \
  > "$RESULT_DIR/instrumentation-output.txt" 2>&1 || INSTRUMENTATION_EXIT=$?
cat "$RESULT_DIR/instrumentation-output.txt"
timeout 20s adb logcat -d -v threadtime > "$RESULT_DIR/logcat.txt" 2>&1 || true

if [ "$INSTRUMENTATION_EXIT" -ne 0 ]; then
  echo "FAIL: dedicated local LLM instrumentation exited with $INSTRUMENTATION_EXIT." | tee -a "$RESULT_DIR/environment.txt"
  exit "$INSTRUMENTATION_EXIT"
fi
if grep -E "FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -2" "$RESULT_DIR/instrumentation-output.txt" >/dev/null; then
  echo "FAIL: Android instrumentation reported a test failure." | tee -a "$RESULT_DIR/environment.txt"
  exit 5
fi
for marker in LOCAL_LLM_FIRST_INFERENCE_PASS LOCAL_LLM_OFFLINE_RESTART_PASS; do
  if ! grep -F "$marker" "$RESULT_DIR/instrumentation-output.txt" "$RESULT_DIR/logcat.txt" >/dev/null 2>&1; then
    echo "FAIL: required actual model marker missing: $marker" | tee -a "$RESULT_DIR/environment.txt"
    exit 6
  fi
  echo "PASS: $marker" | tee -a "$RESULT_DIR/environment.txt"
done
if ! grep -Eq 'OK \\([0-9]+ tests?\\)' "$RESULT_DIR/instrumentation-output.txt"; then
  echo "FAIL: AndroidJUnitRunner did not report a completed passing test." | tee -a "$RESULT_DIR/environment.txt"
  exit 7
fi

echo "PASS: Qwen2-0.5B-Instruct generated text, network was disabled, and a new LiteRT-LM CPU engine generated text again from the verified app-private model file." | tee -a "$RESULT_DIR/environment.txt"
