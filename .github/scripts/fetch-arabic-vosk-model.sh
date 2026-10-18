#!/usr/bin/env bash
set -euo pipefail

MODEL_NAME="vosk-model-ar-mgb2-0.4"
MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
ASSET_ROOT="${1:-eve-project/app/src/main/assets}"
DEST="$ASSET_ROOT/$MODEL_NAME"
ARCHIVE="${RUNNER_TEMP:-/tmp}/$MODEL_NAME.zip"
EXTRACT_ROOT="${RUNNER_TEMP:-/tmp}/$MODEL_NAME-extracted"

mkdir -p "$ASSET_ROOT" "$(dirname "$ARCHIVE")"
echo "Downloading official Arabic Vosk model for offline inclusion: $MODEL_NAME"
curl --fail --location --retry 4 --retry-delay 2 --connect-timeout 30 \
  "$MODEL_URL" --output "$ARCHIVE"

ARCHIVE_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
echo "Arabic speech model archive SHA-256: $ARCHIVE_SHA256"
unzip -Z1 "$ARCHIVE" > "$EXTRACT_ROOT.entries.txt"
if ! grep -Eq '(^|/)conf/model\.conf$' "$EXTRACT_ROOT.entries.txt" ||
   ! grep -Eq '(^|/)am/final\.mdl$' "$EXTRACT_ROOT.entries.txt"; then
  echo "FAIL: the downloaded archive did not contain the expected Vosk acoustic model files."
  exit 1
fi

rm -rf "$EXTRACT_ROOT"
mkdir -p "$EXTRACT_ROOT"
unzip -q "$ARCHIVE" -d "$EXTRACT_ROOT"

SOURCE_DIR="$(find "$EXTRACT_ROOT" -type f -path '*/conf/model.conf' -print -quit | sed 's#/conf/model.conf$##')"
if [ -z "$SOURCE_DIR" ] || [ ! -s "$SOURCE_DIR/am/final.mdl" ]; then
  echo "FAIL: could not locate the extracted Arabic Vosk model root."
  find "$EXTRACT_ROOT" -maxdepth 4 -type f | head -80
  exit 2
fi

rm -rf "$DEST"
mkdir -p "$DEST"
cp -a "$SOURCE_DIR"/. "$DEST"/
# StorageService uses this marker to avoid copying the complete model on every launch.
printf '%s\n' "$ARCHIVE_SHA256" > "$DEST/uuid"

test -s "$DEST/uuid"
test -s "$DEST/conf/model.conf"
test -s "$DEST/am/final.mdl"
echo "PASS: bundled local Arabic speech model is complete."
du -sh "$DEST"
