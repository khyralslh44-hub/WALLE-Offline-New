#!/usr/bin/env bash
set -euo pipefail

MODEL_NAME="vosk-model-ar-mgb2-0.4"
# This GitHub release mirrors the official Vosk archive and publishes a SHA-256.
# Keep the original Vosk host as a verified fallback.
MODEL_URLS=(
  "https://github.com/SymptomTech/notepad-assets/releases/download/v1.0/${MODEL_NAME}.zip"
  "https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"
)
EXPECTED_SHA256="357469ae1bb4d7a3810c9cd6b86d33bc135898dfc134e6df8bc2ddd28c5fe77a"
ASSET_ROOT="${1:-eve-project/app/src/main/assets}"
DEST="$ASSET_ROOT/$MODEL_NAME.zip"
ARCHIVE="${RUNNER_TEMP:-/tmp}/$MODEL_NAME.download.zip"
ENTRIES="${RUNNER_TEMP:-/tmp}/$MODEL_NAME.entries.txt"

mkdir -p "$ASSET_ROOT" "$(dirname "$ARCHIVE")"
rm -f "$ARCHIVE" "$DEST"
DOWNLOADED=false

for MODEL_URL in "${MODEL_URLS[@]}"; do
  echo "Downloading pinned offline Arabic Vosk model from: $MODEL_URL"
  rm -f "$ARCHIVE"
  if ! curl --fail --location --retry 3 --retry-all-errors --retry-delay 2 \
      --connect-timeout 30 --max-time 1200 --speed-time 120 --speed-limit 2048 \
      "$MODEL_URL" --output "$ARCHIVE"; then
    echo "WARN: this Vosk source failed; trying the verified fallback."
    continue
  fi

  ARCHIVE_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
  echo "Arabic speech model archive SHA-256: $ARCHIVE_SHA256"
  if [ "$ARCHIVE_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "WARN: downloaded file hash differs from the pinned Vosk release; discarding it."
    rm -f "$ARCHIVE"
    continue
  fi
  DOWNLOADED=true
  break
done

if [ "$DOWNLOADED" != true ]; then
  echo "FAIL: could not download the pinned Arabic Vosk model from either source."
  exit 1
fi

unzip -Z1 "$ARCHIVE" > "$ENTRIES"
if ! grep -Eq '(^|/)conf/model\.conf$' "$ENTRIES" ||
   ! grep -Eq '(^|/)am/final\.mdl$' "$ENTRIES" ||
   ! grep -Eq '(^|/)graph/HCLG\.fst$' "$ENTRIES"; then
  echo "FAIL: downloaded archive is missing expected Vosk model files."
  exit 1
fi

mv "$ARCHIVE" "$DEST"
test -s "$DEST"
echo "PASS: pinned, checksum-verified offline Arabic Vosk model archive is complete."
ls -lh "$DEST"
du -h "$DEST"
