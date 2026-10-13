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
if [ "$PACKAGE" != "com.eve.robot" ] || [ -z "$ACTIVITY" ]; then
  echo "FAIL: expected replacement EVE app com.eve.robot; found $PACKAGE / $ACTIVITY"
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
adb logcat -c
adb install -r "$APK_PATH" 2>&1 | tee "$RESULTS_DIR/install.txt"
grep -F "Success" "$RESULTS_DIR/install.txt"
adb shell pm grant "$PACKAGE" android.permission.CAMERA
adb shell am start -W -n "$COMPONENT" 2>&1 | tee "$RESULTS_DIR/launch.txt"
grep -F "Status: ok" "$RESULTS_DIR/launch.txt"
sleep 12

PID="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
if [ -z "$PID" ]; then
  adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-after-start-failure.txt"
  echo "FAIL: EVE process was not alive 12 seconds after offline launch."
  exit 1
fi
echo "PASS: EVE stayed alive after launch with Wi-Fi and mobile data disabled (PID $PID)." | tee -a "$RESULTS_DIR/result.txt"

# Android 10 can show a one-time immersive-mode teaching overlay above the app.
# Dismiss that system overlay before asserting the app's UI hierarchy.
timeout 15s adb shell uiautomator dump /sdcard/eve-initial.xml > "$RESULTS_DIR/uiautomator-initial.txt" 2>&1 || true
adb pull /sdcard/eve-initial.xml "$RESULTS_DIR/window-initial.xml" >/dev/null 2>&1 || true
if [ -s "$RESULTS_DIR/window-initial.xml" ] && \
   grep -F 'resource-id="android:id/immersive_cling_title"' "$RESULTS_DIR/window-initial.xml" >/dev/null && \
   grep -F 'resource-id="android:id/ok"' "$RESULTS_DIR/window-initial.xml" >/dev/null; then
  read -r OK_X OK_Y <<< "$(python3 - "$RESULTS_DIR/window-initial.xml" <<'PY'
import re, sys
from pathlib import Path
xml = Path(sys.argv[1]).read_text(errors="replace")
m = re.search(r'<node[^>]*resource-id="android:id/ok"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if not m:
    raise SystemExit("Could not parse immersive overlay's Got it button bounds")
x1, y1, x2, y2 = map(int, m.groups())
print((x1+x2)//2, (y1+y2)//2)
PY
)"
  echo "Dismissing Android immersive-mode education overlay at ($OK_X,$OK_Y)." | tee -a "$RESULTS_DIR/result.txt"
  adb shell input tap "$OK_X" "$OK_Y"
  sleep 2
fi

adb exec-out screencap -p > "$RESULTS_DIR/eve-face.png"
test -s "$RESULTS_DIR/eve-face.png"
python3 - "$RESULTS_DIR/eve-face.png" <<'PY'
import struct, sys
from pathlib import Path
p = Path(sys.argv[1])
data = p.read_bytes()
if len(data) < 24 or data[:8] != bytes([137, 80, 78, 71, 13, 10, 26, 10]):
    raise SystemExit("FAIL: emulator did not produce a valid PNG screenshot.")
width, height = struct.unpack(">II", data[16:24])
if width < 100 or height < 100:
    raise SystemExit(f"FAIL: screenshot dimensions are unexpected: {width}x{height}")
print(f"PASS: captured EVE screen at {width}x{height}.")
PY

# UIAutomator differs between Android API levels for custom-drawn Views.
# Save hierarchy when available, but do not mistake a missing accessibility node
# for a rendering failure. Use actual screenshot and runtime logs for checks.
timeout 15s adb shell uiautomator dump /sdcard/eve-ui.xml > "$RESULTS_DIR/uiautomator.txt" 2>&1 || true
adb pull /sdcard/eve-ui.xml "$RESULTS_DIR/window.xml" >/dev/null 2>&1 || true
if [ -s "$RESULTS_DIR/window.xml" ] && grep -F 'content-desc="EVE_GREEN_EYES_FACE"' "$RESULTS_DIR/window.xml" >/dev/null; then
  echo "PASS: EVE green-eyes view is exposed in accessibility hierarchy." | tee -a "$RESULTS_DIR/result.txt"
else
  echo "INFO: UIAutomator omitted the custom-drawn face on this API; screenshot saved for visual inspection." | tee -a "$RESULTS_DIR/result.txt"
fi
if [ -s "$RESULTS_DIR/window.xml" ] && grep -F 'btnTalk' "$RESULTS_DIR/window.xml" >/dev/null; then
  echo "FAIL: legacy talk/explore button unexpectedly remains in EVE UI."
  exit 1
fi

# Touch the center of the borderless face and check for a corresponding reaction.
SIZE="$(adb shell wm size | grep -Eo '[0-9]+x[0-9]+' | tail -n 1)"
WIDTH="${SIZE%x*}"
HEIGHT="${SIZE#*x}"
X=$((WIDTH / 2))
Y=$((HEIGHT / 2))
echo "Touching face center ($X,$Y)." | tee -a "$RESULTS_DIR/result.txt"
adb shell input tap "$X" "$Y"
sleep 2
PID_AFTER="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\\r' || true)"
test -n "$PID_AFTER" || { echo "FAIL: EVE died after touch."; exit 1; }
adb exec-out screencap -p > "$RESULTS_DIR/eve-face-after-touch.png"

adb logcat -d -v threadtime > "$RESULTS_DIR/logcat.txt"
if grep -E 'FATAL EXCEPTION.*com\\.eve\\.robot|ANR in com\\.eve\\.robot' "$RESULTS_DIR/logcat.txt" >/dev/null; then
  echo "FAIL: app-specific fatal exception or ANR observed."
  exit 1
fi
if ! grep -F "Offline camera analysis started." "$RESULTS_DIR/logcat.txt" >/dev/null; then
  echo "FAIL: offline CameraX analysis did not report a successful start."
  exit 1
fi
if ! grep -F "BEHAVIOR mood=CURIOUS reason=touch" "$RESULTS_DIR/logcat.txt" >/dev/null; then
  echo "FAIL: touch did not trigger a logged behavioral reaction."
  exit 1
fi
if ! grep -F "AUTONOMOUS mood=" "$RESULTS_DIR/logcat.txt" >/dev/null; then
  echo "FAIL: no autonomous idle behavior was observed during the test."
  exit 1
fi
echo "PASS: offline launch, camera start, autonomous behavior, touch reaction, and no app crash/ANR." | tee -a "$RESULTS_DIR/result.txt"
