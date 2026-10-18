#!/usr/bin/env bash
set -euo pipefail

MODEL_NAME="vosk-model-ar-mgb2-0.4"
MODEL_URL="https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip"
ASSET_ROOT="${1:-eve-project/app/src/main/assets}"
DEST="$ASSET_ROOT/$MODEL_NAME.zip"
ARCHIVE="${RUNNER_TEMP:-/tmp}/$MODEL_NAME.download.zip"
ENTRIES="${RUNNER_TEMP:-/tmp}/$MODEL_NAME.entries.txt"

mkdir -p "$ASSET_ROOT" "$(dirname "$ARCHIVE")"
rm -f "$ARCHIVE" "$DEST"
echo "Downloading official Arabic Vosk model archive for offline inclusion: $MODEL_NAME"
curl --fail --location --retry 4 --retry-delay 2 --connect-timeout 30 \
  "$MODEL_URL" --output "$ARCHIVE"

ARCHIVE_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
echo "Arabic speech model archive SHA-256: $ARCHIVE_SHA256"
unzip -Z1 "$ARCHIVE" > "$ENTRIES"
if ! grep -Eq '(^|/)conf/model\.conf$' "$ENTRIES" ||
   ! grep -Eq '(^|/)am/final\.mdl$' "$ENTRIES" ||
   ! grep -Eq '(^|/)graph/HCLG\.fst$' "$ENTRIES"; then
  echo "FAIL: downloaded archive is missing expected Vosk model files."
  exit 1
fi

mv "$ARCHIVE" "$DEST"
test -s "$DEST"
echo "PASS: bundled compressed offline Arabic Vosk model archive is complete."
ls -lh "$DEST"
du -h "$DEST"
