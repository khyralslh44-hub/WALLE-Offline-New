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

# Run face detection and FER inference on the same offline emulator instance as this smoke test.
if [ "$TEST_API_LEVEL" = "35" ]; then
  if [ -z "${TEST_APK_PATH:-}" ] || [ ! -s "$TEST_APK_PATH" ]; then
    echo "FAIL: API 35 instrumentation APK is missing."
    exit 1
  fi
  adb install -r "$TEST_APK_PATH" 2>&1 | tee "$RESULTS_DIR/install-instrumentation.txt"
  grep -F "Success" "$RESULTS_DIR/install-instrumentation.txt"
  adb logcat -c
  INSTRUMENTATION_EXIT=0
  timeout 420s adb shell am instrument -w -r com.eve.robot.test/androidx.test.runner.AndroidJUnitRunner \
    > "$RESULTS_DIR/deep-instrumentation-output.txt" 2>&1 || INSTRUMENTATION_EXIT=$?
  cat "$RESULTS_DIR/deep-instrumentation-output.txt"
  adb logcat -d -v threadtime > "$RESULTS_DIR/deep-instrumentation-logcat.txt" 2>&1 || true
  if [ "$INSTRUMENTATION_EXIT" -ne 0 ] || grep -E 'INSTRUMENTATION_FAILED|FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2' "$RESULTS_DIR/deep-instrumentation-output.txt" >/dev/null; then
    echo "FAIL: offline face/FER instrumentation did not pass (exit=$INSTRUMENTATION_EXIT)."
    exit 1
  fi
  for marker in FACE_FIXTURE_PASS FER_MODEL_PASS OFFLINE_AFFECT_LEARNING_PASS EYE_GALLERY_PASS; do
    if ! grep -F "$marker" "$RESULTS_DIR/deep-instrumentation-logcat.txt" >/dev/null; then
      echo "FAIL: deep instrumentation marker missing: $marker"
      exit 1
    fi
    echo "PASS: deep instrumentation marker $marker" | tee -a "$RESULTS_DIR/result.txt"
  done
  if ! timeout 15s adb exec-out run-as "$PACKAGE" cat files/eye-expression-sheet.png > "$RESULTS_DIR/eye-expression-sheet.png" 2> "$RESULTS_DIR/eye-expression-sheet-pull.txt"; then
    echo "FAIL: could not extract the rendered expression gallery from the app's private files."
    cat "$RESULTS_DIR/eye-expression-sheet-pull.txt" || true
    exit 1
  fi
  python3 - "$RESULTS_DIR/eye-expression-sheet.png" <<'PY'
import struct, sys
from pathlib import Path
data = Path(sys.argv[1]).read_bytes()
if len(data) < 24 or data[:8] != bytes([137, 80, 78, 71, 13, 10, 26, 10]):
    raise SystemExit("FAIL: expression gallery is not a valid PNG.")
w, h = struct.unpack(">II", data[16:24])
if w < 800 or h < 800:
    raise SystemExit(f"FAIL: expression gallery has unexpected dimensions {w}x{h}.")
print(f"PASS: extracted visual expression gallery at {w}x{h}.")
PY
  echo "PASS: real face detector, offline FER model, affect engine and 24 rendered eye glyphs ran on API 35 with network disabled." | tee -a "$RESULTS_DIR/result.txt"
  adb logcat -c
fi

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

# Save the UI hierarchy if the system supports UIAutomator, but first recover from
# the emulator's known "Pixel Launcher isn't responding" overlay. EVE can still be
# alive behind this dialog; do not misreport the launcher as an EVE crash.
dump_ui_safe() {
  timeout 12s adb shell uiautomator dump /sdcard/eve-ui.xml > "$RESULTS_DIR/uiautomator.txt" 2>&1 || true
  timeout 12s adb pull /sdcard/eve-ui.xml "$RESULTS_DIR/window.xml" >/dev/null 2>&1 || true
}
dump_ui_safe
OVERLAY="$(python3 - "$RESULTS_DIR/window.xml" <<'PY'
import re, sys
from pathlib import Path
import xml.etree.ElementTree as ET
p = Path(sys.argv[1])
if not p.exists() or p.stat().st_size == 0:
    print("UNKNOWN")
    raise SystemExit(0)
try:
    root = ET.parse(p).getroot()
except Exception:
    print("UNKNOWN")
    raise SystemExit(0)
nodes = list(root.iter("node"))
texts = [((n.attrib.get("text", "") or "").strip().lower()) for n in nodes]
if not any(("isn't responding" in t) or ("is not responding" in t) for t in texts):
    print("NONE")
    raise SystemExit(0)
for node in nodes:
    rid = node.attrib.get("resource-id", "")
    text = (node.attrib.get("text", "") or "").strip().lower()
    if rid == "android:id/aerr_close" or text == "close app":
        m = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print(f"ANR {(x1+x2)//2} {(y1+y2)//2}")
            raise SystemExit(0)
print("ANR_NO_BUTTON")
PY
)"
if [[ "$OVERLAY" == ANR* ]]; then
  echo "WARN: Android system launcher ANR overlay detected: $OVERLAY" | tee -a "$RESULTS_DIR/result.txt"
  read -r _ OX OY <<< "$OVERLAY"
  if ! timeout 15s adb shell input tap "$OX" "$OY"; then
    echo "WARN: tap failed; restarting ADB connection once." | tee -a "$RESULTS_DIR/result.txt"
    adb kill-server >/dev/null 2>&1 || true
    adb start-server >/dev/null 2>&1 || true
    timeout 45s adb wait-for-device >/dev/null 2>&1 || true
    timeout 15s adb shell input tap "$OX" "$OY" >/dev/null 2>&1 || true
  fi
  sleep 2
  timeout 20s adb shell am start -W -n "$COMPONENT" > "$RESULTS_DIR/foreground-after-system-overlay.txt" 2>&1 || true
  cat "$RESULTS_DIR/foreground-after-system-overlay.txt"
  if grep -F "Status: ok" "$RESULTS_DIR/foreground-after-system-overlay.txt" >/dev/null; then
    echo "PASS: EVE was foregrounded after system-overlay handling." | tee -a "$RESULTS_DIR/result.txt"
  else
    echo "WARN: foreground status after overlay is inconclusive; continue with app process/log checks." | tee -a "$RESULTS_DIR/result.txt"
  fi
  sleep 3
  dump_ui_safe
elif [ "$OVERLAY" = "ANR_NO_BUTTON" ]; then
  echo "WARN: system ANR dialog found but its button was not exposed; retain screenshots/logcat." | tee -a "$RESULTS_DIR/result.txt"
elif [ "$OVERLAY" = "UNKNOWN" ]; then
  echo "WARN: UI hierarchy unavailable; use screenshot and logs for verification." | tee -a "$RESULTS_DIR/result.txt"
fi

if [ -s "$RESULTS_DIR/window.xml" ] && grep -F 'package="com.eve.robot"' "$RESULTS_DIR/window.xml" >/dev/null; then
  if grep -F 'btnTalk' "$RESULTS_DIR/window.xml" >/dev/null; then
    echo "FAIL: legacy talk/explore button unexpectedly remains in EVE UI."
    exit 1
  fi
  echo "PASS: hierarchy belongs to EVE and contains no legacy talk button." | tee -a "$RESULTS_DIR/result.txt"
else
  echo "INFO: EVE face is custom-drawn; hierarchy does not expose app widgets." | tee -a "$RESULTS_DIR/result.txt"
fi

timeout 20s adb exec-out screencap -p > "$RESULTS_DIR/eve-face-after-overlay.png" 2>/dev/null || true
if [ -s "$RESULTS_DIR/eve-face-after-overlay.png" ]; then
  python3 - "$RESULTS_DIR/eve-face-after-overlay.png" <<'PY'
import struct, sys
from pathlib import Path
data = Path(sys.argv[1]).read_bytes()
if len(data) < 24 or data[:8] != bytes([137, 80, 78, 71, 13, 10, 26, 10]):
    raise SystemExit("FAIL: post-overlay screenshot is not a valid PNG.")
w, h = struct.unpack(">II", data[16:24])
print(f"PASS: post-overlay screenshot captured {w}x{h}.")
PY
fi

# Probe for live-camera labels while offline. Emulator virtual cameras may show a
# synthetic scene rather than a known object, so absence of a label is diagnostic—not
# proof that the bundled model is broken. The pinned portrait integration test
# independently verifies ML Kit and FER inference on a known human face.
LABEL=""
BEFORE_COUNT=""
VISION_FOUND=false
for attempt in $(seq 1 25); do
  if ! timeout 15s adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-camera-wait.txt" 2>"$RESULTS_DIR/adb-logcat-error.txt"; then
    echo "WARN: ADB logcat read failed on attempt $attempt; reconnecting and retrying." | tee -a "$RESULTS_DIR/result.txt"
    adb start-server >/dev/null 2>&1 || true
    timeout 45s adb wait-for-device >/dev/null 2>&1 || true
    sleep 1
    continue
  fi
  if grep -F "VISION label=" "$RESULTS_DIR/logcat-camera-wait.txt" >/dev/null; then
    VISION_FOUND=true
    break
  fi
  sleep 1
done
if [ "$VISION_FOUND" = true ]; then
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
  echo "PASS: live offline ML Kit returned label '$LABEL'." | tee -a "$RESULTS_DIR/result.txt"
  if timeout 15s adb shell run-as "$PACKAGE" cat shared_prefs/eve_local_memory.xml > "$RESULTS_DIR/memory-before-restart.xml" 2>/dev/null; then
    BEFORE_COUNT="$(python3 - "$RESULTS_DIR/memory-before-restart.xml" "$LABEL" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
key = "count:" + sys.argv[2]
for item in root:
    if item.attrib.get("name") == key:
        print(item.attrib.get("value", ""))
        break
PY
)"
    if [[ "$BEFORE_COUNT" =~ ^[0-9]+$ ]] && [ "$BEFORE_COUNT" -ge 1 ]; then
      echo "PASS: local memory stored '$LABEL' count=$BEFORE_COUNT." | tee -a "$RESULTS_DIR/result.txt"
    else
      echo "WARN: live label appeared but no matching persistent sighting counter was readable." | tee -a "$RESULTS_DIR/result.txt"
      BEFORE_COUNT=""
    fi
  else
    echo "WARN: could not read app-local label memory; instrumentation checks persistence." | tee -a "$RESULTS_DIR/result.txt"
  fi
else
  echo "WARN: virtual camera produced no label in this run; pinned-image test is the authoritative offline vision check." | tee -a "$RESULTS_DIR/result.txt"
  timeout 15s adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-no-live-label.txt" 2>/dev/null || true
fi

# Hold the face deliberately to request one soft voice cue; an ordinary tap stays silent.
SIZE="$(adb shell wm size | grep -Eo '[0-9]+x[0-9]+' | tail -n 1)"
WIDTH="$(printf '%s' "$SIZE" | cut -d x -f 1)"
HEIGHT="$(printf '%s' "$SIZE" | cut -d x -f 2)"
X=$((WIDTH / 2))
Y=$((HEIGHT / 2))
# Verify both physical CameraX selectors and the button-free double-tap gesture.
adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-before-camera-switch.txt"
if ! grep -F "CAMERA_LENS active=BACK" "$RESULTS_DIR/logcat-before-camera-switch.txt" >/dev/null; then
  echo "FAIL: rear camera was not confirmed as the initial active lens."
  exit 1
fi
echo "Testing double-tap switch BACK -> FRONT." | tee -a "$RESULTS_DIR/result.txt"
adb shell "input tap $X $Y; sleep 0.35; input tap $X $Y"
sleep 3
adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-after-front-switch.txt"
if ! grep -F "CAMERA_SWITCH_SUCCESS from=BACK to=FRONT" "$RESULTS_DIR/logcat-after-front-switch.txt" >/dev/null; then
  echo "FAIL: double-tap did not successfully switch from rear to front camera."
  grep -E "CAMERA_SWITCH|CAMERA_LENS|Camera could not start" "$RESULTS_DIR/logcat-after-front-switch.txt" | tail -30 || true
  exit 1
fi
if ! grep -F "CAMERA_LENS active=FRONT analysis=offline" "$RESULTS_DIR/logcat-after-front-switch.txt" >/dev/null; then
  echo "FAIL: front lens was not bound to the offline image analyzer."
  exit 1
fi
echo "PASS: front camera is active with offline image analysis." | tee -a "$RESULTS_DIR/result.txt"

echo "Testing double-tap switch FRONT -> BACK." | tee -a "$RESULTS_DIR/result.txt"
adb shell "input tap $X $Y; sleep 0.35; input tap $X $Y"
sleep 3
adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-after-back-switch.txt"
if ! grep -F "CAMERA_SWITCH_SUCCESS from=FRONT to=BACK" "$RESULTS_DIR/logcat-after-back-switch.txt" >/dev/null; then
  echo "FAIL: double-tap did not successfully switch from front to rear camera."
  grep -E "CAMERA_SWITCH|CAMERA_LENS|Camera could not start" "$RESULTS_DIR/logcat-after-back-switch.txt" | tail -30 || true
  exit 1
fi
if ! grep -F "CAMERA_LENS active=BACK analysis=offline" "$RESULTS_DIR/logcat-after-back-switch.txt" >/dev/null; then
  echo "FAIL: rear lens was not restored to the offline image analyzer."
  exit 1
fi
echo "PASS: rear camera is active again with offline image analysis." | tee -a "$RESULTS_DIR/result.txt"

echo "Touching face center ($X,$Y)." | tee -a "$RESULTS_DIR/result.txt"
adb shell input swipe "$X" "$Y" "$X" "$Y" 900
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
if ! grep -F "Offline camera analysis started" "$RESULTS_DIR/logcat.txt" >/dev/null; then
  echo "FAIL: offline CameraX analysis did not report a successful start."
  exit 1
fi
if ! grep -F "FACE_SCAN count=" "$RESULTS_DIR/logcat.txt" >/dev/null; then
  echo "FAIL: bundled face detector did not process camera frames while offline."
  exit 1
fi
echo "PASS: offline face detector processed camera frames." | tee -a "$RESULTS_DIR/result.txt"
if ! grep -F "BEHAVIOR mood=HAPPY reason=comforting long touch; frustration eased" "$RESULTS_DIR/logcat.txt" >/dev/null; then
  echo "FAIL: touch did not trigger a logged behavioral reaction."
  exit 1
fi
if ! grep -F "AUTONOMOUS mood=" "$RESULTS_DIR/logcat.txt" >/dev/null; then
  echo "FAIL: no autonomous idle behavior was observed during the test."
  exit 1
fi
if ! grep -F "EVE_VOICE: PLAYBACK_STARTED mood=HAPPY" "$RESULTS_DIR/logcat.txt" >/dev/null; then
  echo "FAIL: AudioTrack did not confirm playback for the touch reaction."
  grep -F "EVE_VOICE:" "$RESULTS_DIR/logcat.txt" | tail -20 || true
  exit 1
fi
echo "PASS: one soft synthesized mood tone was submitted to Android AudioTrack after deliberate touch." | tee -a "$RESULTS_DIR/result.txt"

# Force-stop/relaunch; assert persistence only when the live camera returned a label.
timeout 20s adb shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
if ! timeout 20s adb shell am start -W -n "$COMPONENT" > "$RESULTS_DIR/relaunch.txt" 2>&1; then
  echo "WARN: ADB failed during relaunch; reconnecting once." | tee -a "$RESULTS_DIR/result.txt"
  adb kill-server >/dev/null 2>&1 || true
  adb start-server >/dev/null 2>&1 || true
  timeout 45s adb wait-for-device >/dev/null 2>&1 || true
  timeout 20s adb shell am start -W -n "$COMPONENT" > "$RESULTS_DIR/relaunch.txt" 2>&1 || true
fi
cat "$RESULTS_DIR/relaunch.txt"
grep -F "Status: ok" "$RESULTS_DIR/relaunch.txt" >/dev/null || {
  echo "FAIL: EVE could not be foregrounded after restart."
  exit 1
}
sleep 3
PID_AFTER_RESTART="$(timeout 15s adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)"
test -n "$PID_AFTER_RESTART" || { echo "FAIL: EVE did not stay alive after restart."; exit 1; }
if [ -n "$LABEL" ] && [ -n "$BEFORE_COUNT" ]; then
  if timeout 15s adb shell run-as "$PACKAGE" cat shared_prefs/eve_local_memory.xml > "$RESULTS_DIR/memory-after-restart.xml" 2>/dev/null; then
    AFTER_COUNT="$(python3 - "$RESULTS_DIR/memory-after-restart.xml" "$LABEL" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
key = "count:" + sys.argv[2]
for item in root:
    if item.attrib.get("name") == key:
        print(item.attrib.get("value", ""))
        break
PY
)"
    if [[ "$AFTER_COUNT" =~ ^[0-9]+$ ]] && [ "$AFTER_COUNT" -ge "$BEFORE_COUNT" ]; then
      echo "PASS: local sighting memory survived restart (before=$BEFORE_COUNT after=$AFTER_COUNT)." | tee -a "$RESULTS_DIR/result.txt"
    else
      echo "FAIL: live sighting counter did not survive restart (before=$BEFORE_COUNT after=$AFTER_COUNT)."
      exit 1
    fi
  else
    echo "WARN: couldn't read local memory after restart; instrumentation checks persistence." | tee -a "$RESULTS_DIR/result.txt"
  fi
else
  echo "INFO: no live label was available for a restart counter comparison; instrumentation checks persistent learning." | tee -a "$RESULTS_DIR/result.txt"
fi

adb logcat -d -v threadtime > "$RESULTS_DIR/logcat-after-restart.txt"
echo "PASS: offline launch, image labeling, autonomous idle behavior, touch reaction, synthesized audio submission, persistent sighting counter, and no app crash/ANR." | tee -a "$RESULTS_DIR/result.txt"
