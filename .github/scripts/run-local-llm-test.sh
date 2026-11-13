#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must be set}"
: "${ANDROID_HOME:?ANDROID_HOME must be set}"

RESULT_DIR="$GITHUB_WORKSPACE/eve-tiny-results"
APP_APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
mkdir -p "$RESULT_DIR"

SOURCE_ROOT="$GITHUB_WORKSPACE/eve-project/app/src/main"
GRADLE_FILE="$GITHUB_WORKSPACE/eve-project/app/build.gradle.kts"
if grep -R -nE 'EveArabicLlmRuntime|eve-arabic-50m-q4|dev\.ffmpegkit\.maintained:llama-android' "$SOURCE_ROOT" "$GRADLE_FILE"; then
  echo "FAIL: obsolete pretrained LLM path or llama.cpp dependency is still referenced." | tee "$RESULT_DIR/environment.txt"
  exit 1
fi

echo "EVE Bespoke Compact Arabic Brain Android verification" | tee "$RESULT_DIR/environment.txt"
echo "Expected: compact Arabic fallback works offline; LiteRT-LM runtime is packaged, while Qwen3 weights remain a separate user-approved download." | tee -a "$RESULT_DIR/environment.txt"

for APK in "$APP_APK" "$TEST_APK"; do
  if [ ! -s "$APK" ]; then
    echo "FAIL: missing APK $APK" | tee -a "$RESULT_DIR/environment.txt"
    exit 2
  fi
done

APP_BYTES="$(stat -c '%s' "$APP_APK")"
echo "Debug APK bytes: $APP_BYTES" | tee -a "$RESULT_DIR/environment.txt"
if [ "$APP_BYTES" -gt 300000000 ]; then
  echo "FAIL: APK exceeds the 300 MB fallback-test ceiling before any generative model weights." | tee -a "$RESULT_DIR/environment.txt"
  exit 3
fi

APK_ENTRIES="$RESULT_DIR/apk-entries.txt"
unzip -Z1 "$APP_APK" > "$APK_ENTRIES"
MODEL_WEIGHTS_PATTERN='(^|/)models/[^/]*\.(gguf|safetensors|pt|pth)$|\.(litertlm|gguf|safetensors|pt|pth)$|libllama(_jni)?\.so'
if grep -Eiq "$MODEL_WEIGHTS_PATTERN" "$APK_ENTRIES"; then
  echo "FAIL: generative-model weights are present in this fallback-only APK." | tee -a "$RESULT_DIR/environment.txt"
  grep -Ein "$MODEL_WEIGHTS_PATTERN" "$APK_ENTRIES" | tee -a "$RESULT_DIR/environment.txt" || true
  exit 4
fi
if ! unzip -p "$APP_APK" "classes*.dex" | strings | grep -F "EveLiteRtLanguageModel" > /dev/null; then
  echo "FAIL: LiteRT-LM integration is missing from the app APK." | tee -a "$RESULT_DIR/environment.txt"
  exit 4
fi
echo "PASS: LiteRT-LM integration is packaged, Qwen3 weights remain external, and no llama.cpp library is bundled." | tee -a "$RESULT_DIR/environment.txt"

wait_for_device() {
  adb start-server >/dev/null 2>&1 || true
  timeout 60s adb wait-for-device
  for attempt in $(seq 1 90); do
    BOOT="$(timeout 8s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [ "$BOOT" = "1" ]; then
      return 0
    fi
    sleep 2
  done
  echo "FAIL: Android emulator did not finish booting." | tee -a "$RESULT_DIR/environment.txt"
  return 1
}

wait_for_device
adb install -r "$APP_APK" | tee "$RESULT_DIR/install-app.txt"
adb install -r "$TEST_APK" | tee "$RESULT_DIR/install-test.txt"
adb shell pm clear com.eve.robot | tee "$RESULT_DIR/clear-app-data.txt"

# Disable Wi-Fi and mobile data, then enable airplane mode before exercising EVE.
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

BADGING="$RESULT_DIR/test-apk-badging.txt"
if ! "$AAPT" dump badging "$TEST_APK" > "$BADGING"; then
  echo "FAIL: could not read instrumentation APK metadata." | tee -a "$RESULT_DIR/environment.txt"
  exit 6
fi
TEST_PACKAGE="$(awk -F"'" '/^package: name=/{print $2; exit}' "$BADGING")"
if [ -z "$TEST_PACKAGE" ]; then
  echo "FAIL: instrumentation package not found in APK metadata." | tee -a "$RESULT_DIR/environment.txt"
  exit 6
fi
echo "Instrumentation package: $TEST_PACKAGE" | tee -a "$RESULT_DIR/environment.txt"

INSTRUMENTATION_EXIT=0
timeout 180s adb shell am instrument -w -r \
  -e class com.eve.robot.LocalLanguageBrainIntegrationTest \
  "$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner" \
  > "$RESULT_DIR/instrumentation-output.txt" 2>&1 || INSTRUMENTATION_EXIT=$?
cat "$RESULT_DIR/instrumentation-output.txt"
adb logcat -d -v threadtime > "$RESULT_DIR/logcat.txt" 2>&1 || true

if [ "$INSTRUMENTATION_EXIT" -ne 0 ] ||
   grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -2' "$RESULT_DIR/instrumentation-output.txt"; then
  echo "FAIL: bespoke Arabic Brain instrumentation failed (exit=$INSTRUMENTATION_EXIT)." | tee -a "$RESULT_DIR/environment.txt"
  exit 7
fi
if ! grep -Eq 'OK \([0-9]+ tests?\)' "$RESULT_DIR/instrumentation-output.txt"; then
  echo "FAIL: AndroidJUnitRunner did not report completed passing tests." | tee -a "$RESULT_DIR/environment.txt"
  exit 8
fi
if ! grep -Fq 'TINY_ARABIC_BRAIN_ANDROID_PASS' "$RESULT_DIR/logcat.txt"; then
  echo "FAIL: expected local brain success marker missing from logcat." | tee -a "$RESULT_DIR/environment.txt"
  exit 9
fi
if ! grep -Fq 'TINY_ARABIC_BRAIN_SPEED_PASS' "$RESULT_DIR/logcat.txt"; then
  echo "FAIL: response-generation performance marker missing from logcat." | tee -a "$RESULT_DIR/environment.txt"
  exit 10
fi
if ! grep -Fq 'TINY_ARABIC_DIALECT_PASS' "$RESULT_DIR/logcat.txt"; then
  echo "FAIL: Arabic dialect classification marker missing from Android runtime." | tee -a "$RESULT_DIR/environment.txt"
  exit 11
fi
if ! grep -Fq 'TINY_ARABIC_SEMANTIC_COMPOSITION_PASS' "$RESULT_DIR/logcat.txt"; then
  echo "FAIL: context-grounded Arabic semantic composition marker missing from Android runtime." | tee -a "$RESULT_DIR/environment.txt"
  exit 12
fi

echo "PASS: EVE's compact Arabic fallback ran on Android with Wi-Fi/mobile data disabled; generative Qwen3 weights were not downloaded or bundled." | tee -a "$RESULT_DIR/environment.txt"
