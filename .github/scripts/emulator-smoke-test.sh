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

# Wait for an actual ML Kit label while Wi-Fi and mobile data are disabled.
# Seeing only "camera started" is not enough to prove the classifier returned a result.
VISION_FOUND=false
for attempt in $(seq 1 35); do
  adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-camera-wait.txt"
  if grep -F "VISION label=" "$RESULTS_DIR/logcat-camera-wait.txt" >/dev/null; then
    VISION_FOUND=true
    break
  fi
  sleep 1
done
if [ "$VISION_FOUND" != true ]; then
  echo "FAIL: no ML Kit image label was returned while offline."
  adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-no-vision.txt"
  exit 1
fi
LABEL="$(python3 - "$RESULTS_DIR/logcat-camera-wait.txt" <<'PY'
import re, sys
from pathlib import Path
log = Path(sys.argv[1]).read_text(errors="replace")
m = re.search(r'VISION label=(.*?) confidence=', log)
if not m:
    raise SystemExit("FAIL: a camera label could not be parsed from logcat.")
print(m.group(1).strip().lower())
PY
)"
echo "PASS: offline ML Kit returned label '$LABEL'." | tee -a "$RESULTS_DIR/result.txt"

# Verify the label counter is actually persisted to private SharedPreferences.
sleep 1
adb shell run-as "$PACKAGE" cat shared_prefs/eve_local_memory.xml > "$RESULTS_DIR/memory-before-restart.xml" || {
  echo "FAIL: could not read the app's local memory file."
  exit 1
}
BEFORE_COUNT="$(python3 - "$RESULTS_DIR/memory-before-restart.xml" "$LABEL" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
key = "count:" + sys.argv[2]
for item in root:
    if item.attrib.get("name") == key:
        print(item.attrib.get("value", ""))
        break
else:
    raise SystemExit(f"FAIL: no saved sighting counter for {key}")
PY
)"
if ! [[ "$BEFORE_COUNT" =~ ^[0-9]+$ ]] || [ "$BEFORE_COUNT" -lt 1 ]; then
  echo "FAIL: stored sighting counter is not a positive integer."
  exit 1
fi
echo "PASS: local memory saved '$LABEL' with sighting count $BEFORE_COUNT." | tee -a "$RESULTS_DIR/result.txt"

# The emulator sometimes raises a system ANR dialog for Pixel Launcher above the app.
# Dismiss it before tapping so the test tap reaches EVE rather than a system window.
dump_ui() {
  timeout 12s adb shell uiautomator dump /sdcard/eve-pre-touch.xml > "$RESULTS_DIR/uiautomator-pre-touch.txt" 2>&1 || true
  adb pull /sdcard/eve-pre-touch.xml "$RESULTS_DIR/window-pre-touch.xml" >/dev/null 2>&1 || true
}
dump_ui
OVERLAY_RESULT="$(python3 - "$RESULTS_DIR/window-pre-touch.xml" <<'PY'
import re, sys
from pathlib import Path
import xml.etree.ElementTree as ET
p = Path(sys.argv[1])
if not p.exists() or p.stat().st_size == 0:
    print("UNKNOWN")
    raise SystemExit(0)
root = ET.parse(p).getroot()
nodes = list(root.iter("node"))
texts = [(node.attrib.get("text", "") or "").strip().lower() for node in nodes]
has_anr = any(("isn't responding" in t) or ("is not responding" in t) or ("not responding" in t) for t in texts)
if not has_anr:
    print("NONE")
    raise SystemExit(0)
targets = [
    ("android:id/aerr_close", "close app"),
    ("", "close app"),
    ("android:id/aerr_wait", "wait"),
    ("", "wait"),
]
for resource, text in targets:
    for node in nodes:
        if resource and node.attrib.get("resource-id") != resource:
            continue
        if not resource and (node.attrib.get("text", "") or "").strip().lower() != text:
            continue
        bounds = node.attrib.get("bounds", "")
        m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print(f"ANR {text} {(x1+x2)//2} {(y1+y2)//2}")
            raise SystemExit(0)
print("ANR_NO_BUTTON")
PY
)"
if [[ "$OVERLAY_RESULT" == ANR* ]]; then
  echo "WARN: system ANR overlay detected before touch: $OVERLAY_RESULT" | tee -a "$RESULTS_DIR/result.txt"
  read -r _ OVERLAY_ACTION OX OY <<< "$OVERLAY_RESULT"
  if [ -n "$OX" ] && [ -n "$OY" ]; then
    adb shell input tap "$OX" "$OY"
    sleep 2
    adb shell am start -W -n "$COMPONENT" 2>&1 | tee "$RESULTS_DIR/foreground-after-system-overlay.txt"
    grep -F "Status: ok" "$RESULTS_DIR/foreground-after-system-overlay.txt"
    sleep 2
    dump_ui
    STILL_ANR="$(python3 - "$RESULTS_DIR/window-pre-touch.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
from pathlib import Path
p = Path(sys.argv[1])
if not p.exists() or p.stat().st_size == 0:
    print("UNKNOWN")
else:
    root = ET.parse(p).getroot()
    texts = [((n.attrib.get("text", "") or "").strip().lower()) for n in root.iter("node")]
    print("YES" if any(("isn't responding" in t) or ("is not responding" in t) or ("not responding" in t) for t in texts) else "NO")
PY
)"
    if [ "$STILL_ANR" = "YES" ]; then
      echo "FAIL: system ANR overlay remained above EVE after dismissal."
      exit 1
    fi
    echo "PASS: system overlay was dismissed and EVE was foregrounded again." | tee -a "$RESULTS_DIR/result.txt"
  else
    echo "FAIL: system ANR overlay was detected but its button could not be located."
    exit 1
  fi
elif [ "$OVERLAY_RESULT" = "UNKNOWN" ]; then
  echo "WARN: UI hierarchy unavailable before touch; retaining screenshot/logcat for review." | tee -a "$RESULTS_DIR/result.txt"
fi

# Touch the center of the face and require a mood reaction plus an AudioTrack play call.
SIZE="$(adb shell wm size | grep -Eo '[0-9]+x[0-9]+' | tail -n 1)"
WIDTH="$(printf '%s' "$SIZE" | cut -d x -f 1)"
HEIGHT="$(printf '%s' "$SIZE" | cut -d x -f 2)"
X=$((WIDTH / 2))
Y=$((HEIGHT / 2))
echo "Touching face center ($X,$Y)." | tee -a "$RESULTS_DIR/result.txt"
adb shell input tap "$X" "$Y"
sleep 2
PID_AFTER="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
test -n "$PID_AFTER" || { echo "FAIL: EVE died after touch."; exit 1; }
adb exec-out screencap -p > "$RESULTS_DIR/eve-face-after-touch.png"

adb logcat -d -v threadtime > "$RESULTS_DIR/logcat.txt"
python3 - "$RESULTS_DIR/logcat.txt" "$PACKAGE" <<'PY'
import sys
from pathlib import Path
lines = Path(sys.argv[1]).read_text(errors="replace").splitlines()
package = sys.argv[2]
fatal = []
anr = []
for index, line in enumerate(lines):
    context = "\n".join(lines[index:index + 45])
    if "FATAL EXCEPTION" in line and (f"Process: {package}" in context or package in context):
        fatal.append(context)
    if f"ANR in {package}" in line or f"Application Not Responding: {package}" in line:
        anr.append(context)
if fatal or anr:
    parts = []
    if fatal:
        parts.append("App fatal exception:\n" + "\n---\n".join(fatal[:2]))
    if anr:
        parts.append("App ANR:\n" + "\n---\n".join(anr[:2]))
    print("FAIL: app-specific crash/ANR detected:\n" + "\n".join(parts))
    raise SystemExit(1)
system_anr = [line for line in lines if "ANR in " in line and package not in line]
if system_anr:
    Path(sys.argv[1]).with_name("system-anr-observations.txt").write_text(
        "\n".join(system_anr) + "\n"
    )
    print("WARN: system ANR unrelated to EVE recorded separately.")
print("PASS: no app-specific fatal exception or ANR found in logcat.")
PY
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
if ! grep -F "EVE_VOICE: PLAYBACK_STARTED mood=CURIOUS" "$RESULTS_DIR/logcat.txt" >/dev/null; then
  echo "FAIL: AudioTrack did not confirm playback for the touch reaction."
  grep -F "EVE_VOICE:" "$RESULTS_DIR/logcat.txt" | tail -20 || true
  exit 1
fi
echo "PASS: a synthesized mood chirp was submitted to Android AudioTrack." | tee -a "$RESULTS_DIR/result.txt"

# Force-stop/relaunch and verify that the saved label count persists.
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$COMPONENT" 2>&1 | tee "$RESULTS_DIR/relaunch.txt"
grep -F "Status: ok" "$RESULTS_DIR/relaunch.txt"
sleep 3
PID_AFTER_RESTART="$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
test -n "$PID_AFTER_RESTART" || { echo "FAIL: EVE did not stay alive after restart."; exit 1; }
adb shell run-as "$PACKAGE" cat shared_prefs/eve_local_memory.xml > "$RESULTS_DIR/memory-after-restart.xml" || {
  echo "FAIL: could not read local memory after restart."
  exit 1
}
AFTER_COUNT="$(python3 - "$RESULTS_DIR/memory-after-restart.xml" "$LABEL" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
key = "count:" + sys.argv[2]
for item in root:
    if item.attrib.get("name") == key:
        print(item.attrib.get("value", ""))
        break
else:
    raise SystemExit(f"FAIL: local memory key {key} disappeared after restart")
PY
)"
if ! [[ "$AFTER_COUNT" =~ ^[0-9]+$ ]] || [ "$AFTER_COUNT" -lt "$BEFORE_COUNT" ]; then
  echo "FAIL: saved sighting count did not survive restart (before=$BEFORE_COUNT after=$AFTER_COUNT)."
  exit 1
fi
echo "PASS: local sighting memory survived force-stop/relaunch (before=$BEFORE_COUNT after=$AFTER_COUNT)." | tee -a "$RESULTS_DIR/result.txt"

adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-after-restart.txt"
echo "PASS: offline launch, image labeling, autonomous idle behavior, touch reaction, synthesized audio submission, persistent sighting counter, and no app crash/ANR." | tee -a "$RESULTS_DIR/result.txt"
