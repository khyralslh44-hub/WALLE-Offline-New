#!/usr/bin/env bash
set -euo pipefail

MODEL_ID="Sharjeelbaig/moonshine-streaming-tiny-ar-onnx"
MODEL_DIR="${1:-${RUNNER_TEMP:-/tmp}/eve-moonshine-arabic}"
mkdir -p "$MODEL_DIR"

API_JSON="$(curl --fail --location --retry 3 "https://huggingface.co/api/models/$MODEL_ID")"
REVISION="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["sha"])' <<< "$API_JSON")"
if [[ ! "$REVISION" =~ ^[0-9a-f]{40}$ ]]; then
  echo "FAIL: Hugging Face did not return an immutable 40-character model revision."
  exit 1
fi
printf '%s\n' "$REVISION" | tee "$MODEL_DIR/revision.txt"

FILES=(
  "encoder_model_quantized.onnx"
  "decoder_model_quantized.onnx"
  "tokenizer.json"
  "config.json"
  "model_meta.json"
)
for FILE in "${FILES[@]}"; do
  URL="https://huggingface.co/$MODEL_ID/resolve/$REVISION/$FILE"
  echo "Downloading $FILE from immutable revision $REVISION"
  curl --fail --location --retry 3 --connect-timeout 20 --max-time 600 "$URL" --output "$MODEL_DIR/$FILE"
  test -s "$MODEL_DIR/$FILE"
  sha256sum "$MODEL_DIR/$FILE"
done

python3 .github/scripts/validate-moonshine-arabic-onnx.py "$MODEL_DIR"
echo "PASS: Moonshine Arabic candidate files are structurally loadable by ONNX Runtime."
echo "NOTE: this validates packaging and graph loading only; it does not establish transcription accuracy or Redmi 12 speed."
