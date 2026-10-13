#!/usr/bin/env bash
set -euo pipefail

RESULTS_DIR="$GITHUB_WORKSPACE/emulator-test-results/api-$TEST_API_LEVEL"
mkdir -p "$RESULTS_DIR"

{
  echo "API level: $TEST_API_LEVEL"
  echo "Build run ID: $SOURCE_RUN_ID"
  echo "APK path: $APK_PATH"
} | tee "$RESULTS_DIR/result.txt"

test -s "$APK_PATH"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
AAPT="$(find "$SDK_ROOT/build-tools" -type f -name aapt -print 2>/dev/null | sort -V | tail -n 1)"
if [ -z "$AAPT" ] || [ ! -x "$AAPT" ]; then
  echo "FAIL: Could not find aapt in $SDK_ROOT/build-tools"
  exit 1
fi

PACKAGE="$("$AAPT" dump badging "$APK_PATH" | awk -F"'" '/^package: name=/{print $2; exit}')"
if [ -z "$PACKAGE" ]; then
  echo "FAIL: Could not read application ID from APK."
  exit 1
fi
echo "Application ID: $PACKAGE" | tee -a "$RESULTS_DIR/result.txt"

adb wait-for-device
adb shell svc wifi disable || true
adb shell svc data disable || true
adb shell settings get global airplane_mode_on > "$RESULTS_DIR/airplane-mode-setting.txt" || true

adb logcat -c
adb install -r "$APK_PATH" 2>&1 | tee "$RESULTS_DIR/install.txt"
grep -F "Success" "$RESULTS_DIR/install.txt"

adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 2>&1 | tee "$RESULTS_DIR/launch.txt"
sleep 12
PID="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [ -z "$PID" ]; then
  adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-after-start-failure.txt"
  adb shell dumpsys activity activities > "$RESULTS_DIR/activities-after-start-failure.txt" || true
  echo "FAIL: application process was not alive 12 seconds after launch."
  exit 1
fi
echo "PASS: process stayed alive after offline cold launch (PID $PID)." | tee -a "$RESULTS_DIR/result.txt"

adb shell dumpsys activity activities > "$RESULTS_DIR/activities.txt"
if ! grep -F "$PACKAGE/" "$RESULTS_DIR/activities.txt" > "$RESULTS_DIR/foreground-activity.txt"; then
  echo "FAIL: app activity was not found in ActivityManager output."
  exit 1
fi

# Capture the screen before the optional hierarchy dump in case accessibility hangs.
adb exec-out screencap -p > "$RESULTS_DIR/emulator-screen.png"
test -s "$RESULTS_DIR/emulator-screen.png"
timeout 15s adb shell uiautomator dump /sdcard/walle-ui.xml > "$RESULTS_DIR/uiautomator.txt" 2>&1 || true
adb pull /sdcard/walle-ui.xml "$RESULTS_DIR/window.xml" >/dev/null 2>&1 || true

adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 2>&1 | tee "$RESULTS_DIR/relaunch.txt"
sleep 4
PID2="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [ -z "$PID2" ]; then
  adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-after-resume-failure.txt"
  echo "FAIL: app process was not alive after background/resume."
  exit 1
fi
echo "PASS: app could be relaunched from background." | tee -a "$RESULTS_DIR/result.txt"

adb logcat -d -v threadtime > "$RESULTS_DIR/logcat.txt"
python3 - "$RESULTS_DIR/logcat.txt" "$PACKAGE" <<'PY'
import sys
from pathlib import Path
log = Path(sys.argv[1]).read_text(errors="replace").splitlines()
package = sys.argv[2]
for i, line in enumerate(log):
    if "FATAL EXCEPTION" in line:
        block = "\n".join(log[i:i+35])
        if package in block:
            print("FAIL: fatal exception for app detected:\n" + block)
            raise SystemExit(1)
print("PASS: no app-specific FATAL EXCEPTION found in logcat.")
PY
echo "PASS: emulator smoke test completed." | tee -a "$RESULTS_DIR/result.txt"
