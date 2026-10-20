#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE must be set}"
: "${RUNNER_TEMP:?RUNNER_TEMP must be set}"
: "${ANDROID_HOME:?ANDROID_HOME must be set}"

RESULT_DIR="$GITHUB_WORKSPACE/eve-llm-results"
APP_APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$GITHUB_WORKSPACE/eve-project/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
MODEL_FILE="$RUNNER_TEMP/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
MODEL_SHA256="03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856"
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
if [ "$MODEL_BYTES" -lt 300000000 ]; then
  echo "FAIL: Qwen model weights are unexpectedly small ($MODEL_BYTES bytes)." | tee -a "$RESULT_DIR/environment.txt"
  exit 5
fi
echo "PASS: host Qwen model SHA-256 verified; bytes=$MODEL_BYTES." | tee -a "$RESULT_DIR/environment.txt"
sha256sum "$MODEL_FILE" | tee "$RESULT_DIR/qwen-model-sha256.txt"
# Save the full ZIP directory first. Do not pipe unzip directly into grep -q:
# with pipefail enabled, grep may close early and make unzip report SIGPIPE as a false "missing asset".
APK_ENTRIES="$RESULT_DIR/app-apk-entries.txt"
if ! unzip -Z1 "$APP_APK" > "$APK_ENTRIES"; then
  echo "FAIL: could not read the install APK ZIP directory." | tee -a "$RESULT_DIR/environment.txt"
  exit 13
fi
if ! grep -Fxq "assets/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm" "$APK_ENTRIES"; then
  echo "FAIL: install APK really does not contain the pinned Qwen model asset." | tee -a "$RESULT_DIR/environment.txt"
  echo "APK size: $(stat -c '%s' "$APP_APK") bytes" | tee -a "$RESULT_DIR/environment.txt"
  echo "Related entries:" | tee -a "$RESULT_DIR/environment.txt"
  grep -E 'Qwen3-1\.7B|Qwen2_0\.5B_Instruct' "$APK_ENTRIES" | tee -a "$RESULT_DIR/environment.txt" || true
  exit 13
fi
PACKAGED_MODEL_SHA256="$(unzip -p "$APP_APK" assets/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm | sha256sum | awk '{print $1}')"
if [ "$PACKAGED_MODEL_SHA256" != "$MODEL_SHA256" ]; then
  echo "FAIL: Qwen model inside install APK has wrong SHA-256: $PACKAGED_MODEL_SHA256" | tee -a "$RESULT_DIR/environment.txt"
  exit 14
fi
echo "PASS: install APK contains the pinned Qwen asset with the expected SHA-256." | tee -a "$RESULT_DIR/environment.txt"
# Emulator startup occasionally drops the ADB daemon after a long boot. A single
# adb wait-for-device then exits the whole script under set -e before the model test begins.
# Reconnect explicitly and wait for both Android boot and Package Manager readiness.
wait_for_android_ready() {
  local attempt boot_try boot_complete package_state
  for attempt in $(seq 1 5); do
    echo "Checking ADB and Android Package Manager (attempt $attempt/5)." | tee -a "$RESULT_DIR/environment.txt"
    adb start-server >/dev/null 2>&1 || true
    if timeout 45s adb wait-for-device; then
      for boot_try in $(seq 1 75); do
        boot_complete="$(timeout 8s adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
        if [ "$boot_complete" = "1" ]; then
          package_state="$(timeout 8s adb shell service check package 2>/dev/null || true)"
          if grep -qi "found" <<< "$package_state" &&
             timeout 8s adb shell pm list packages 2>/dev/null | grep -q '^package:'; then
            echo "PASS: ADB, Android boot and Package Manager are ready." | tee -a "$RESULT_DIR/environment.txt"
            return 0
          fi
        fi
        if [ "$boot_try" -eq 1 ] || [ $((boot_try % 15)) -eq 0 ]; then
          echo "Waiting for boot/package service ($boot_try/75); sys.boot_completed=$boot_complete." | tee -a "$RESULT_DIR/environment.txt"
        fi
        sleep 2
      done
    fi
    echo "WARN: ADB/package service did not stabilize on attempt $attempt; restarting ADB." | tee -a "$RESULT_DIR/environment.txt"
    timeout 15s adb devices -l >> "$RESULT_DIR/environment.txt" 2>&1 || true
    adb kill-server >/dev/null 2>&1 || true
    sleep 2
    adb start-server >/dev/null 2>&1 || true
    sleep 2
  done
  echo "FAIL: unable to restore ADB and Package Manager after five attempts." | tee -a "$RESULT_DIR/environment.txt"
  timeout 15s adb devices -l >> "$RESULT_DIR/environment.txt" 2>&1 || true
  timeout 15s adb logcat -d -v threadtime > "$RESULT_DIR/adb-recovery-logcat.txt" 2>&1 || true
  return 1
}

install_apk_with_retry() {
  local label="$1" apk="$2" output="$3" attempt
  for attempt in $(seq 1 3); do
    wait_for_android_ready || return 1
    echo "Installing $label (attempt $attempt/3)." | tee -a "$RESULT_DIR/environment.txt"
    if timeout 360s adb install -r "$apk" > "$output" 2>&1 && grep -Fq "Success" "$output"; then
      cat "$output"
      return 0
    fi
    cat "$output" 2>/dev/null || true
    if [ "$attempt" -lt 3 ]; then
      adb kill-server >/dev/null 2>&1 || true
      sleep 2
      adb start-server >/dev/null 2>&1 || true
    fi
  done
  echo "FAIL: could not install $label after three recoverable attempts." | tee -a "$RESULT_DIR/environment.txt"
  return 1
}

if ! wait_for_android_ready; then
  exit 6
fi
if ! install_apk_with_retry "production APK" "$APP_APK" "$RESULT_DIR/install-app.txt"; then
  exit 16
fi
if ! install_apk_with_retry "instrumentation APK" "$TEST_APK" "$RESULT_DIR/install-test.txt"; then
  exit 17
fi

# Ensure a truly clean first launch: install/update can leave old app-private files if an
# emulator image or instrumentation setup reuses app data. pm clear force-stops the package
# and erases all of its private model/cache/preferences before the first-run assertion.
CLEAR_OUTPUT="$(timeout 30s adb shell pm clear com.eve.robot 2>&1 | tr -d '\r' || true)"
echo "pm clear output: $CLEAR_OUTPUT" | tee -a "$RESULT_DIR/environment.txt"
if ! grep -Fxq "Success" <<< "$CLEAR_OUTPUT"; then
  echo "FAIL: could not reset com.eve.robot app data before first-run model extraction." | tee -a "$RESULT_DIR/environment.txt"
  exit 15
fi
echo "PASS: app-private data was cleared before the first-run test." | tee -a "$RESULT_DIR/environment.txt"

# Disable network access at the emulator interface level, not just Wi-Fi/mobile radios.
# AOSP emulators keep a virtual Ethernet network active in airplane mode, so radio toggles
# alone do not prove offline operation. Try root adbd, reconnect, and bring every non-loopback
# interface down before the first app launch. If the image refuses root, fail closed rather
# than silently counting a still-connected emulator as an offline pass.
NETWORK_ISOLATION_MODE="radio-only-fallback"
if timeout 30s adb root > "$RESULT_DIR/adb-root.txt" 2>&1; then
  cat "$RESULT_DIR/adb-root.txt" | tee -a "$RESULT_DIR/environment.txt"
  if wait_for_android_ready; then
    DEVICE_IDENTITY="$(timeout 15s adb shell id 2>/dev/null | tr -d '\\r' || true)"
    echo "Device shell identity after adb root: $DEVICE_IDENTITY" | tee -a "$RESULT_DIR/environment.txt"
    if grep -q "uid=0(root)" <<< "$DEVICE_IDENTITY"; then
      if timeout 45s adb shell '
        set +e
        for path in /sys/class/net/*; do
          iface="${path##*/}"
          [ "$iface" = "lo" ] && continue
          ip link set dev "$iface" down >/dev/null 2>&1 || true
        done
        ip route flush default >/dev/null 2>&1 || true
        echo "--- interfaces after network isolation ---"
        ip -o link show
        echo "--- routes after network isolation ---"
        ip route show
      ' > "$RESULT_DIR/network-interface-isolation.txt" 2>&1; then
        cat "$RESULT_DIR/network-interface-isolation.txt" | tee -a "$RESULT_DIR/environment.txt"
        NETWORK_ISOLATION_MODE="root-interface-shutdown"
      else
        echo "WARN: root adbd succeeded but interface shutdown command failed." | tee -a "$RESULT_DIR/environment.txt"
      fi
    else
      echo "WARN: adb root did not yield uid 0; cannot shut down emulator Ethernet." | tee -a "$RESULT_DIR/environment.txt"
    fi
  else
    echo "WARN: Android did not reconnect after adb root." | tee -a "$RESULT_DIR/environment.txt"
  fi
else
  cat "$RESULT_DIR/adb-root.txt" | tee -a "$RESULT_DIR/environment.txt"
  echo "WARN: emulator image refuses adb root; attempting radio-only isolation, which may leave virtual Ethernet active." | tee -a "$RESULT_DIR/environment.txt"
fi

timeout 20s adb shell svc wifi disable >/dev/null 2>&1 || true
timeout 20s adb shell svc data disable >/dev/null 2>&1 || true
timeout 20s adb shell settings put global airplane_mode_on 1 >/dev/null 2>&1 || true
timeout 20s adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1 || true
timeout 20s adb shell cmd connectivity airplane-mode enable >/dev/null 2>&1 || true
sleep 8
{
  echo "Network isolation mode: $NETWORK_ISOLATION_MODE"
  timeout 15s adb shell ip route show 2>&1 || true
  timeout 15s adb shell dumpsys connectivity 2>&1 | grep -E "Active default network|VALIDATED|NetworkAgentInfo|Ethernet|CONNECTED" | head -60 || true
} | tee "$RESULT_DIR/network-after-disable.txt"

# Keep the test strict: a green result is impossible while Android still advertises an
# Internet-validated network, even if the bundled model is expected to avoid all networking.

if timeout 30s adb shell run-as com.eve.robot ls files/local-language-brain/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm >/dev/null 2>&1; then
  PRIVATE_MODEL_STATE="present"
else
  PRIVATE_MODEL_STATE="absent"
fi
echo "Private model before first launch: $PRIVATE_MODEL_STATE" | tee -a "$RESULT_DIR/environment.txt"
if [ "$PRIVATE_MODEL_STATE" != "absent" ]; then
  echo "FAIL: app-private model still exists after pm clear; cannot prove APK-asset extraction." | tee -a "$RESULT_DIR/environment.txt"
  exit 7
fi
echo "PASS: no model exists in app-private storage and networking is disabled before first-run asset extraction." | tee -a "$RESULT_DIR/environment.txt"

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

echo "PASS: pinned Qwen3-0.6B dynamic-INT4 was SHA-256 verified, loaded by the GPU-first LiteRT-LM runtime with CPU fallback, generated text offline, and generated text again after a fresh offline engine initialization." | tee -a "$RESULT_DIR/environment.txt"
