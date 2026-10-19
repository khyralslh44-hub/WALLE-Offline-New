#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must be set}"
: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"
: "${ANDROID_HOME:?ANDROID_HOME must be set}"

RESULT_DIR="$GITHUB_WORKSPACE/eve-llm-results"
APP_APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
MODEL_FILE="$RUNNER_TEMP/Qwen2_0.5B_Instruct.litertlm"
MODEL_SHA256="0f01cc004b8eb62b92ba6be85ed05a248ba0d2f78af94c4949b313eccfb4c157"
mkdir -p "$RESULT_DIR"

echo "EVE real local language-model verification" | tee "$RESULT_DIR/environment.txt"
echo "Expected: verify Qwen weights inside the APK, disable network before first launch, extract from APK assets, generate a reply, close the engine, reload from private storage, and generate again offline." | tee -a "$RESULT_DIR/environment.txt"

for APK in "$APP_APK" "$TEST_APK"; do
  if [ ! -s "$APK" ]; then
    echo "FAIL: missing built APK $APK" | tee -a "$RESULT_DIR/environment.txt"
    exit 2
  fi
done
if [ ! -s "$MODEL_FILE" ]; then
  echo "FAIL: pinned Qwen model was not downloaded on the build runner." | tee -a "$RESULT_DIR/environment.txt"
  exit 3
fi
ACTUAL_MODEL_SHA256="$(sha256sum "$MODEL_FILE" | awk '{print $1}')"
if [ "$ACTUAL_MODEL_SHA256" != "$MODEL_SHA256" ]; then
  echo "FAIL: Qwen model SHA-256 mismatch: $ACTUAL_MODEL_SHA256" | tee -a "$RESULT_DIR/environment.txt"
  exit 4
fi
MODEL_BYTES="$(stat -c '%s' "$MODEL_FILE")"
if [ "$MODEL_BYTES" -lt 500000000 ]; then
  echo "FAIL: Qwen model weights are unexpectedly small ($MODEL_BYTES bytes)." | tee -a "$RESULT_DIR/environment.txt"
  exit 5
fi
echo "PASS: host Qwen model SHA-256 verified; bytes=$MODEL_BYTES." | tee -a "$RESULT_DIR/environment.txt"
sha256sum "$MODEL_FILE" | tee "$RESULT_DIR/qwen-model-sha256.txt"
if ! unzip -Z1 "$APP_APK" | grep -Fxq "assets/Qwen2_0.5B_Instruct.litertlm"; then
  echo "FAIL: install APK does not contain the pinned Qwen model asset." | tee -a "$RESULT_DIR/environment.txt"
  exit 13
fi
echo "PASS: install APK contains the verified Qwen model asset." | tee -a "$RESULT_DIR/environment.txt"
timeout 90s adb wait-for-device
BOOT_COMPLETE="$(timeout 20s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
if [ "$BOOT_COMPLETE" != "1" ]; then
  echo "FAIL: Android emulator was not boot-complete." | tee -a "$RESULT_DIR/environment.txt"
  exit 6
fi

timeout 240s adb install -r "$APP_APK" > "$RESULT_DIR/install-app.txt" 2>&1
cat "$RESULT_DIR/install-app.txt"
timeout 240s adb install -r "$TEST_APK" > "$RESULT_DIR/install-test.txt" 2>&1
cat "$RESULT_DIR/install-test.txt"

# The app must extract weights from APK assets; do not push or pre-stage the model.
# This proves first-launch initialization can occur with Wi-Fi and mobile data disabled.
PRIVATE_MODEL_STATE="$(timeout 30s adb shell run-as com.eve.robot sh -c 'if [ -f files/local-language-brain/Qwen2_0.5B_Instruct.litertlm ]; then echo present; else echo absent; fi' 2>/dev/null | tr -d '\\r' || true)"
if [ "$PRIVATE_MODEL_STATE" != "absent" ]; then
  echo "FAIL: test emulator already had a private model; cannot prove APK-asset extraction." | tee -a "$RESULT_DIR/environment.txt"
  exit 7
fi
echo "PASS: no model exists in app-private storage before first-run asset extraction." | tee -a "$RESULT_DIR/environment.txt"

# Disconnect before first inference: no remote fallback is available to the model under test.
timeout 20s adb shell svc wifi disable >/dev/null 2>&1 || true
timeout 20s adb shell svc data disable >/dev/null 2>&1 || true
timeout 20s adb shell settings put global airplane_mode_on 1 >/dev/null 2>&1 || true
timeout 20s adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1 || true
sleep 6
timeout 15s adb logcat -c >/dev/null 2>&1 || true

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
AAPT="$(find "$SDK_ROOT/build-tools" -type f -name aapt -print 2>/dev/null | sort -V | tail -n 1)"
if [ -z "$AAPT" ]; then
  echo "FAIL: aapt not found." | tee -a "$RESULT_DIR/environment.txt"
  exit 8
fi
TEST_PACKAGE="$("$AAPT" dump badging "$TEST_APK" | sed -n "s/^package: name='\\([^']*\\)'.*/\\1/p" | head -n 1)"
if [ -z "$TEST_PACKAGE" ]; then
  echo "FAIL: could not read instrumentation package." | tee -a "$RESULT_DIR/environment.txt"
  exit 9
fi
echo "Instrumentation package: $TEST_PACKAGE" | tee -a "$RESULT_DIR/environment.txt"
echo "Starting dedicated LocalLanguageBrainIntegrationTest with Wi-Fi and mobile data disabled." | tee -a "$RESULT_DIR/environment.txt"

INSTRUMENTATION_EXIT=0
timeout 1800s adb shell am instrument -w -r \
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
  exit 10
fi
for marker in LOCAL_LLM_FIRST_INFERENCE_PASS LOCAL_LLM_OFFLINE_RESTART_PASS; do
  if ! grep -F "$marker" "$RESULT_DIR/instrumentation-output.txt" "$RESULT_DIR/logcat.txt" >/dev/null 2>&1; then
    echo "FAIL: required real-model inference marker missing: $marker" | tee -a "$RESULT_DIR/environment.txt"
    exit 11
  fi
  echo "PASS: $marker" | tee -a "$RESULT_DIR/environment.txt"
done
if ! grep -Eq 'OK \\([0-9]+ tests?\\)' "$RESULT_DIR/instrumentation-output.txt"; then
  echo "FAIL: AndroidJUnitRunner did not report a completed passing test." | tee -a "$RESULT_DIR/environment.txt"
  exit 12
fi

echo "PASS: pinned Qwen2-0.5B-Instruct was SHA-256 verified, loaded by LiteRT-LM CPU, generated text offline, and generated text again after a fresh offline engine initialization." | tee -a "$RESULT_DIR/environment.txt"
