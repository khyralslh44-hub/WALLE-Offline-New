#!/usr/bin/env bash
set -euo pipefail

ASSET_DIR="eve-project/app/src/main/assets/models"
MODEL="$ASSET_DIR/eve-qwen3-0.6b-q4km.gguf"
CHECKSUM="$ASSET_DIR/eve-qwen3-0.6b-q4km.sha256"
LICENSE="$ASSET_DIR/Apache-2.0-LICENSE.txt"
NOTICE="$ASSET_DIR/QWEN3-MODEL-NOTICE.txt"
REPORT_DIR="eve-model-build"

MODEL_REPO="unsloth/Qwen3-0.6B-GGUF"
MODEL_REVISION="f2d6f9c"
MODEL_FILE="Qwen3-0.6B-Q4_K_M.gguf"
MODEL_URL="https://huggingface.co/$MODEL_REPO/resolve/$MODEL_REVISION/$MODEL_FILE"
EXPECTED_SHA256="ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a"

mkdir -p "$ASSET_DIR" "$REPORT_DIR"

# Download a ready-made, pinned GGUF. No Python/PyTorch conversion, tokenizer
# patching, or quantization is required in the Android build.
if [ ! -s "$MODEL" ]; then
  rm -f "$MODEL.part"
  curl --fail --location --retry 5 --retry-delay 3 \
    --connect-timeout 30 --max-time 1200 \
    "$MODEL_URL" --output "$MODEL.part"
  mv "$MODEL.part" "$MODEL"
fi

MODEL_BYTES="$(stat -c '%s' "$MODEL")"
if [ "$MODEL_BYTES" -lt 350000000 ] || [ "$MODEL_BYTES" -gt 450000000 ]; then
  echo "FAIL: Qwen3 GGUF size is outside the expected 350-450 MB range: $MODEL_BYTES"
  exit 20
fi

MAGIC="$(head -c 4 "$MODEL")"
if [ "$MAGIC" != "GGUF" ]; then
  echo "FAIL: downloaded model does not have a GGUF header."
  exit 21
fi

ACTUAL_SHA256="$(sha256sum "$MODEL" | awk '{print $1}')"
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
  echo "FAIL: Qwen3 GGUF checksum mismatch. Expected $EXPECTED_SHA256, got $ACTUAL_SHA256"
  exit 22
fi

printf '%s  %s\n' "$ACTUAL_SHA256" "$(basename "$MODEL")" > "$CHECKSUM"

if [ ! -s "$LICENSE" ]; then
  curl --fail --location --retry 3 --connect-timeout 20 --max-time 90 \
    "https://www.apache.org/licenses/LICENSE-2.0.txt" --output "$LICENSE"
fi
grep -Fq "Apache License" "$LICENSE"

cat > "$NOTICE" <<EOF
EVE local language model
Model: Qwen/Qwen3-0.6B (instruction-tuned multilingual model)
GGUF distribution: unsloth/Qwen3-0.6B-GGUF
Quantization: Q4_K_M
Pinned GGUF revision: $MODEL_REVISION
File: $MODEL_FILE
SHA-256: $ACTUAL_SHA256
Bytes: $MODEL_BYTES
Base model license: Apache-2.0 (see Apache-2.0-LICENSE.txt)
Model card: https://huggingface.co/Qwen/Qwen3-0.6B
GGUF source: https://huggingface.co/$MODEL_REPO
Purpose: local Arabic dialogue generation only. Camera perception, face matching,
emotion inference, memory, and behavior planning remain in separate on-device modules.
Limitations: a compact 0.6B model can make mistakes and is not a frontier assistant.
EOF

{
  echo "model_repo=$MODEL_REPO"
  echo "model_revision=$MODEL_REVISION"
  echo "model_file=$MODEL_FILE"
  echo "quantization=Q4_K_M"
  echo "gguf_sha256=$ACTUAL_SHA256"
  echo "gguf_bytes=$MODEL_BYTES"
  echo "cacheable=true"
} | tee "$REPORT_DIR/qwen3-model-build-report.txt"

echo "PASS: pinned Qwen3-0.6B Q4_K_M GGUF verified and ready for offline APK packaging."
