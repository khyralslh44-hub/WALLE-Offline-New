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

BADGING="$("$AAPT" dump badging "$APK_PATH")"
PACKAGE="$(printf '%s\n' "$BADGING" | awk -F"'" '/^package: name=/{print $2; exit}')"
ACTIVITY="$(printf '%s\n' "$BADGING" | awk -F"'" '/^launchable-activity: name=/{print $2; exit}')"
if [ -z "$PACKAGE" ] || [ -z "$ACTIVITY" ]; then
  echo "FAIL: Could not read package or launcher activity from APK."
  exit 1
fi
case "$ACTIVITY" in
  .*) COMPONENT="$PACKAGE/$ACTIVITY" ;;
  *.*) COMPONENT="$PACKAGE/$ACTIVITY" ;;
  *) COMPONENT="$PACKAGE/.$ACTIVITY" ;;
esac
echo "Application ID: $PACKAGE" | tee -a "$RESULTS_DIR/result.txt"
echo "Launch component: $COMPONENT" | tee -a "$RESULTS_DIR/result.txt"

adb wait-for-device
adb shell svc wifi disable || true
adb shell svc data disable || true
adb shell settings get global airplane_mode_on > "$RESULTS_DIR/airplane-mode-setting.txt" || true
adb shell dumpsys connectivity > "$RESULTS_DIR/connectivity-offline.txt" || true

adb logcat -c
adb install -r "$APK_PATH" 2>&1 | tee "$RESULTS_DIR/install.txt"
grep -F "Success" "$RESULTS_DIR/install.txt"

# Start the app activity directly instead of going through Pixel Launcher.
adb shell am start -W -n "$COMPONENT" 2>&1 | tee "$RESULTS_DIR/launch.txt"
grep -F "Status: ok" "$RESULTS_DIR/launch.txt"
sleep 10

PID="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [ -z "$PID" ]; then
  adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-after-start-failure.txt"
  adb shell dumpsys activity activities > "$RESULTS_DIR/activities-after-start-failure.txt" || true
  echo "FAIL: application process was not alive 10 seconds after launch."
  exit 1
fi
echo "PASS: process stayed alive after offline cold launch (PID $PID)." | tee -a "$RESULTS_DIR/result.txt"

adb shell dumpsys activity activities > "$RESULTS_DIR/activities.txt"
if ! grep -F "$PACKAGE/" "$RESULTS_DIR/activities.txt" > "$RESULTS_DIR/foreground-activity.txt"; then
  echo "FAIL: app activity was not found in ActivityManager output."
  exit 1
fi

# Verify that the expected app UI, including its explore button, is actually present.
adb exec-out screencap -p > "$RESULTS_DIR/emulator-screen.png"
test -s "$RESULTS_DIR/emulator-screen.png"
timeout 15s adb shell uiautomator dump /sdcard/walle-ui.xml > "$RESULTS_DIR/uiautomator.txt" 2>&1 || true
adb pull /sdcard/walle-ui.xml "$RESULTS_DIR/window.xml" >/dev/null 2>&1 || true
if [ ! -s "$RESULTS_DIR/window.xml" ] || ! grep -F "resource-id=\"$PACKAGE:id/btnTalk\"" "$RESULTS_DIR/window.xml" >/dev/null; then
  echo "FAIL: expected application UI or explore button is missing from the UI hierarchy."
  adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-ui-failure.txt"
  exit 1
fi
echo "PASS: application UI and explore button are present." | tee -a "$RESULTS_DIR/result.txt"

# Tap the main explore/camera button, then confirm the app process survives.
read -r TAP_X TAP_Y <<< "$(python3 - "$RESULTS_DIR/window.xml" "$PACKAGE:id/btnTalk" <<'PY'
import re, sys
from pathlib import Path
xml = Path(sys.argv[1]).read_text(errors="replace")
resource = re.escape(sys.argv[2])
m = re.search(r'<node[^>]*resource-id="' + resource + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if not m:
    raise SystemExit("Explore button bounds were not found")
x1, y1, x2, y2 = map(int, m.groups())
print((x1 + x2) // 2, (y1 + y2) // 2)
PY
)"
echo "Explore button test tap at ($TAP_X,$TAP_Y)." | tee -a "$RESULTS_DIR/result.txt"
adb shell input tap "$TAP_X" "$TAP_Y"
sleep 4
PID_AFTER_TAP="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [ -z "$PID_AFTER_TAP" ]; then
  adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-after-button-failure.txt"
  echo "FAIL: application process died after tapping the explore button."
  exit 1
fi
adb exec-out screencap -p > "$RESULTS_DIR/after-explore-button.png"
echo "PASS: application process survived the explore-button tap." | tee -a "$RESULTS_DIR/result.txt"

# Return to the app without routing through the home launcher.
adb shell am start -W -n "$COMPONENT" 2>&1 | tee "$RESULTS_DIR/relaunch.txt"
sleep 4
PID2="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [ -z "$PID2" ]; then
  adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-after-resume-failure.txt"
  echo "FAIL: app process was not alive after direct relaunch."
  exit 1
fi
echo "PASS: app could be returned to by a second activity launch." | tee -a "$RESULTS_DIR/result.txt"

adb logcat -d -v threadtime > "$RESULTS_DIR/logcat.txt"
python3 - "$RESULTS_DIR/logcat.txt" "$PACKAGE" <<'PY'
import sys
from pathlib import Path
log = Path(sys.argv[1]).read_text(errors="replace").splitlines()
package = sys.argv[2]
app_fatals = []
app_anrs = []
system_launcher_anrs = []
for i, line in enumerate(log):
    block = "\n".join(log[i:i+45])
    if "FATAL EXCEPTION" in line and package in block:
        app_fatals.append(block)
    if ("ANR in " + package) in line or ("Application Not Responding: " + package) in line:
        app_anrs.append(block)
    if "Application Not Responding: com.google.android.apps.nexuslauncher" in line:
        system_launcher_anrs.append(line)
if app_fatals:
    print("FAIL: app-specific fatal exception detected:\n" + "\n---\n".join(app_fatals[:2]))
    raise SystemExit(1)
if app_anrs:
    print("FAIL: app-specific ANR detected:\n" + "\n---\n".join(app_anrs[:2]))
    raise SystemExit(1)
if system_launcher_anrs:
    Path(sys.argv[1]).with_name("system-launcher-anr-observations.txt").write_text("\n".join(system_launcher_anrs) + "\n")
    print("WARN: emulator system launcher ANR observed; the app itself had no app-specific ANR.")
else:
    print("PASS: no app-specific FATAL EXCEPTION or ANR found in logcat.")
PY
echo "PASS: emulator smoke test completed." | tee -a "$RESULTS_DIR/result.txt"
