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

MANIFEST="$GITHUB_WORKSPACE/eve-project/app/src/main/AndroidManifest.xml"
if grep -Eq 'android.permission.INTERNET' "$MANIFEST"; then
  echo "FAIL: app manifest requests INTERNET permission; offline-only invariant broken." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi
adb wait-for-device
# Disable Wi-Fi if the emulator supports it. Do not call 'svc data disable': API 29
# emulator's Telephony service can be null and crash the system command, killing ADB.
adb shell svc wifi disable || true
adb logcat -c

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

if ! grep -F "OFFLINE_AFFECT_LEARNING_PASS" "$RESULT_DIR/logcat.txt" "$GRADLE_LOG" >/dev/null 2>&1; then
  echo "FAIL: offline preference-learning persistence was not confirmed by instrumentation." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi

if [ ! -f "$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk" ]; then
  echo "FAIL: expected debug APK was not generated." | tee -a "$RESULT_DIR/environment.txt"
  exit 1
fi
sha256sum "$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk" | tee "$RESULT_DIR/apk-sha256.txt"
echo "PASS: bundled face model, stable face cues, affect decision, generated eye profile, offline preference memory, unit tests, and APK build all completed offline." | tee -a "$RESULT_DIR/environment.txt"
