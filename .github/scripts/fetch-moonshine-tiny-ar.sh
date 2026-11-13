#!/usr/bin/env bash
set -euo pipefail

# Moonshine Tiny Arabic: pinned 27.1M-parameter student ASR, exported to int8 ONNX.
# Upstream revision is immutable; both model files are SHA-256 verified.
REVISION="28d27f3"
MODEL_REPO="onnx-community/moonshine-tiny-ar-ONNX"
ASSETS="eve-project/app/src/main/assets"
mkdir -p "$ASSETS"

fetch_pinned() {
  local remote="$1"
  local output="$2"
  local expected_sha="$3"
  local min_bytes="$4"
  local url="https://huggingface.co/$MODEL_REPO/resolve/$REVISION/$remote"

  curl --fail --location --retry 4 --retry-all-errors --retry-delay 2 \
    --connect-timeout 30 --max-time 900 "$url" --output "$output"
  local bytes
  local actual_sha
  bytes="$(stat -c '%s' "$output")"
  actual_sha="$(sha256sum "$output" | awk '{print $1}')"
  echo "$output bytes=$bytes sha256=$actual_sha"
  if [ "$bytes" -lt "$min_bytes" ] || [ "$actual_sha" != "$expected_sha" ]; then
    echo "FAIL: $remote did not match the pinned model file."
    exit 1
  fi
}

fetch_pinned "onnx/encoder_model_int8.onnx" \
  "$ASSETS/eve_moonshine_ar_encoder_int8.onnx" \
  "189c63a96dbedcbce32987ccb2ef52367b5392744fdc9b05f3d5442b0db4e85f" 7000000

fetch_pinned "onnx/decoder_model_merged_int8.onnx" \
  "$ASSETS/eve_moonshine_ar_decoder_int8.onnx" \
  "c3d7481beb40f0880155195a12c619e035fd67249b8d0315d90f7f32dca4d380" 18000000

TOKENIZER="$RUNNER_TEMP/eve-moonshine-ar-tokenizer.json"
curl --fail --location --retry 4 --retry-all-errors --retry-delay 2 \
  --connect-timeout 30 --max-time 300 \
  "https://huggingface.co/$MODEL_REPO/resolve/$REVISION/tokenizer.json" \
  --output "$TOKENIZER"
TOKENIZER_BYTES="$(stat -c '%s' "$TOKENIZER")"
if [ "$TOKENIZER_BYTES" -lt 3000000 ] || [ "$TOKENIZER_BYTES" -gt 5000000 ]; then
  echo "FAIL: Moonshine tokenizer.json has an unexpected size: $TOKENIZER_BYTES"
  exit 1
fi
python3 .github/scripts/create-moonshine-tokens.py \
  "$TOKENIZER" "$ASSETS/eve_moonshine_ar_tokens.txt"
test -s "$ASSETS/eve_moonshine_ar_tokens.txt"

# Keep the original model license and attribution alongside the converted weights.
LICENSE_URL="https://raw.githubusercontent.com/moonshine-ai/moonshine/d8d8fa29d05ca67441a7719e70099f9f48917a93/LICENSE"
curl --fail --location --retry 3 --connect-timeout 20 --max-time 120 \
  "$LICENSE_URL" --output "$ASSETS/moonshine_ai_community_license.txt"
grep -Fq "MOONSHINE AI COMMUNITY LICENSE AGREEMENT" \
  "$ASSETS/moonshine_ai_community_license.txt"
cat > "$ASSETS/moonshine_ai_notice.txt" <<'NOTICE'
This EVE build uses the Moonshine Tiny Arabic speech-recognition model.
The Arabic Tiny model is licensed under the Moonshine AI Community License.
Model publisher: Moonshine AI (formerly Useful Sensors, Inc.).
Model card: https://huggingface.co/moonshine-ai/moonshine-tiny-ar
Runtime: sherpa-onnx, https://github.com/k2-fsa/sherpa-onnx
NOTICE

echo "PASS: compact Arabic Moonshine model, tokenizer, and license are ready."
du -h "$ASSETS"/eve_moonshine_ar_* "$ASSETS"/moonshine_ai_*
