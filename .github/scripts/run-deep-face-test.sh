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
echo "Offline invariant: no INTERNET permission; local ML Kit + TFLite inference required." | tee -a "$RESULT_DIR/environment.txt"

# Preserve diagnostics even if emulator setup, ADB, Gradle or instrumentation fails.
capture_diagnostics() {
  local code=$?
  trap - EXIT
  {
    echo "Final test exit code: $code"
    echo "--- adb devices ---"
    timeout 10s adb devices -l 2>&1 || true
    echo "--- emulator state ---"
    timeout 10s adb get-state 2>&1 || true
    echo "--- boot complete ---"
    timeout 10s adb shell getprop sys.boot_completed 2>&1 || true
    echo "--- recent logcat ---"
    timeout 15s adb logcat -d -v threadtime 2>&1 | tail -n 250 || true
  } > "$RESULT_DIR/diagnostics-on-exit.txt"
  exit "$code"
}
trap capture_diagnostics EXIT

MANIFEST="$GITHUB_WORKSPACE/eve-project/app/src/main/AndroidManifest.xml"
if grep -Eq 'android.permission.INTERNET' "$MANIFEST"; then
  echo "FAIL: app manifest requests INTERNET permission." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi
if [ ! -f "$GITHUB_WORKSPACE/eve-project/app/src/androidTest/assets/face_smile.png" ]; then
  echo "FAIL: pinned face fixture is missing." | tee -a "$RESULT_DIR/environment.txt"
  exit 2
fi
if [ ! -f "$GITHUB_WORKSPACE/eve-project/app/src/main/assets/eve_emotion_model.tflite" ]; then
  echo "FAIL: bundled emotion model is missing." | tee -a "$RESULT_DIR/environment.txt"
  exit 3
fi
if [ ! -x "$GRADLE" ]; then
  echo "FAIL: Gradle executable not found at $GRADLE" | tee -a "$RESULT_DIR/environment.txt"
  exit 4
fi

echo "--- ADB devices before test ---" | tee -a "$RESULT_DIR/environment.txt"
timeout 15s adb devices -l 2>&1 | tee "$RESULT_DIR/adb-devices-before.txt" || true
if ! timeout 90s adb wait-for-device >> "$RESULT_DIR/environment.txt" 2>&1; then
  echo "FAIL: ADB could not reach emulator within 90 seconds." | tee -a "$RESULT_DIR/environment.txt"
  exit 70
fi
if ! timeout 15s adb get-state 2>&1 | grep -q '^device$'; then
  echo "FAIL: ADB has no usable device after wait-for-device." | tee -a "$RESULT_DIR/environment.txt"
  exit 71
fi
echo "PASS: ADB transport ready." | tee -a "$RESULT_DIR/environment.txt"

# Disable network paths in the emulator. The production manifest also deliberately omits INTERNET.
timeout 10s adb shell svc wifi disable >> "$RESULT_DIR/environment.txt" 2>&1 || true
timeout 10s adb shell svc data disable >> "$RESULT_DIR/environment.txt" 2>&1 || true
timeout 10s adb logcat -c >/dev/null 2>&1 || echo "WARN: logcat buffer could not be cleared." | tee -a "$RESULT_DIR/environment.txt"

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
  echo "FAIL: Gradle/unit/instrumentation exited with status $GRADLE_EXIT." | tee -a "$RESULT_DIR/environment.txt"
  exit "$GRADLE_EXIT"
fi
for marker in FACE_FIXTURE_PASS FER_MODEL_PASS OFFLINE_AFFECT_LEARNING_PASS; do
  if ! grep -F "$marker" "$RESULT_DIR/logcat.txt" "$GRADLE_LOG" >/dev/null 2>&1; then
    echo "FAIL: required instrumentation marker missing: $marker" | tee -a "$RESULT_DIR/environment.txt"
    echo "Inspect gradle-test.log, logcat.txt, test-signals.txt and androidTests report." | tee -a "$RESULT_DIR/environment.txt"
    exit 1
  fi
  echo "PASS: instrumentation marker $marker" | tee -a "$RESULT_DIR/environment.txt"
done

APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
  echo "FAIL: debug APK was not generated." | tee -a "$RESULT_DIR/environment.txt"
  exit 5
fi
sha256sum "$APK" | tee "$RESULT_DIR/apk-sha256.txt"
echo "PASS: unit tests, offline face detection, TFLite expression inference, affect-to-eye mapping, offline preference memory and APK build." | tee -a "$RESULT_DIR/environment.txt"
