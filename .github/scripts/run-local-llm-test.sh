#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must be set}"
: "${ANDROID_HOME:?ANDROID_HOME must be set}"

RESULT_DIR="$GITHUB_WORKSPACE/eve-tiny-results"
APP_APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
mkdir -p "$RESULT_DIR"

echo "EVE Tiny Arabic Brain Android verification" | tee "$RESULT_DIR/environment.txt"
echo "Expected: a compact on-device Arabic intent model, no Qwen/LiteRT-LM weights, no network request, and no dropped user question." | tee -a "$RESULT_DIR/environment.txt"

for APK in "$APP_APK" "$TEST_APK"; do
  if [ ! -s "$APK" ]; then
    echo "FAIL: missing APK $APK" | tee -a "$RESULT_DIR/environment.txt"
    exit 2
  fi
done

APP_BYTES="$(stat -c '%s' "$APP_APK")"
echo "Debug APK bytes: $APP_BYTES" | tee -a "$RESULT_DIR/environment.txt"
if [ "$APP_BYTES" -gt 180000000 ]; then
  echo "FAIL: APK is unexpectedly large for the lightweight language brain." | tee -a "$RESULT_DIR/environment.txt"
  exit 3
fi

APK_ENTRIES="$RESULT_DIR/apk-entries.txt"
unzip -Z1 "$APP_APK" > "$APK_ENTRIES"
if grep -Eiq 'Qwen|litertlm|llama' "$APK_ENTRIES"; then
  echo "FAIL: a large external LLM asset remains in the APK." | tee -a "$RESULT_DIR/environment.txt"
  grep -Ei 'Qwen|litertlm|llama' "$APK_ENTRIES" | tee -a "$RESULT_DIR/environment.txt"
  exit 4
fi
echo "PASS: no Qwen, LiteRT-LM, or llama weights are packaged." | tee -a "$RESULT_DIR/environment.txt"

wait_for_device() {
  adb start-server >/dev/null 2>&1 || true
  timeout 60s adb wait-for-device
  for attempt in $(seq 1 90); do
    BOOT="$(timeout 8s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [ "$BOOT" = "1" ]; then return 0; fi
    sleep 2
  done
  echo "FAIL: Android emulator did not finish booting." | tee -a "$RESULT_DIR/environment.txt"
  return 1
}

wait_for_device
adb install -r "$APP_APK" | tee "$RESULT_DIR/install-app.txt"
adb install -r "$TEST_APK" | tee "$RESULT_DIR/install-test.txt"
adb shell pm clear com.eve.robot | tee "$RESULT_DIR/clear-app-data.txt"

# The local language brain must initialize and answer after the emulator has no working radios.
adb shell svc wifi disable >/dev/null 2>&1 || true
adb shell svc data disable >/dev/null 2>&1 || true
adb shell settings put global airplane_mode_on 1 >/dev/null 2>&1 || true
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1 || true
sleep 5
adb shell dumpsys connectivity > "$RESULT_DIR/connectivity.txt" 2>&1 || true

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
AAPT="$(find "$SDK_ROOT/build-tools" -type f -name aapt -print 2>/dev/null | sort -V | tail -n 1)"
if [ -z "$AAPT" ]; then
  echo "FAIL: aapt not found." | tee -a "$RESULT_DIR/environment.txt"
  exit 5
fi
TEST_PACKAGE="$("$AAPT" dump badging "$TEST_APK" | sed -n "s/^package: name='\\([^']*\\)'.*/\\1/p" | head -n 1)"
if [ -z "$TEST_PACKAGE" ]; then
  echo "FAIL: instrumentation package not found." | tee -a "$RESULT_DIR/environment.txt"
  exit 6
fi

INSTRUMENTATION_EXIT=0
timeout 180s adb shell am instrument -w -r \
  -e class com.eve.robot.LocalLanguageBrainIntegrationTest \
  "$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner" \
  > "$RESULT_DIR/instrumentation-output.txt" 2>&1 || INSTRUMENTATION_EXIT=$?
cat "$RESULT_DIR/instrumentation-output.txt"
adb logcat -d -v threadtime > "$RESULT_DIR/logcat.txt" 2>&1 || true

if [ "$INSTRUMENTATION_EXIT" -ne 0 ] ||
   grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -2' "$RESULT_DIR/instrumentation-output.txt"; then
  echo "FAIL: Tiny Arabic Brain instrumentation failed (exit=$INSTRUMENTATION_EXIT)." | tee -a "$RESULT_DIR/environment.txt"
  exit 7
fi
if ! grep -Eq 'OK \\([0-9]+ tests?\\)' "$RESULT_DIR/instrumentation-output.txt"; then
  echo "FAIL: AndroidJUnitRunner did not report completed passing tests." | tee -a "$RESULT_DIR/environment.txt"
  exit 8
fi
if ! grep -Fq 'TINY_ARABIC_BRAIN_ANDROID_PASS' "$RESULT_DIR/logcat.txt"; then
  echo "FAIL: expected local brain success marker missing from logcat." | tee -a "$RESULT_DIR/environment.txt"
  exit 9
fi

echo "PASS: EVE Tiny Arabic Brain ran on Android with radios disabled and no bundled large language model." | tee -a "$RESULT_DIR/environment.txt"
