#!/usr/bin/env bash
set -euo pipefail

MODEL_DIR="\${1:?Usage: export_gguf.sh <trained-hf-model-dir> [output-dir]}"
OUT_DIR="\${2:-eve-model/export}"
LLAMA_CPP_REVISION="2da668617612d2df773f966e3b0ee22dc2beef7b"
LLAMA_DIR="\${LLAMA_CPP_DIR:-$PWD/.cache/llama.cpp}"

mkdir -p "$OUT_DIR" "$(dirname "$LLAMA_DIR")"
if [ ! -d "$LLAMA_DIR/.git" ]; then
  git clone https://github.com/ggml-org/llama.cpp.git "$LLAMA_DIR"
fi
git -C "$LLAMA_DIR" fetch --depth 1 origin "$LLAMA_CPP_REVISION"
git -C "$LLAMA_DIR" checkout --detach "$LLAMA_CPP_REVISION"

python "$LLAMA_DIR/convert_hf_to_gguf.py" "$MODEL_DIR" \
  --outfile "$OUT_DIR/eve-ar-95m-f16.gguf" --outtype f16
cmake -S "$LLAMA_DIR" -B "$LLAMA_DIR/build" \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF
cmake --build "$LLAMA_DIR/build" --config Release --parallel 2 --target llama-quantize
"$LLAMA_DIR/build/bin/llama-quantize" \
  "$OUT_DIR/eve-ar-95m-f16.gguf" \
  "$OUT_DIR/eve-ar-95m-q4_k_m.gguf" Q4_K_M
test -s "$OUT_DIR/eve-ar-95m-q4_k_m.gguf"
sha256sum "$OUT_DIR/eve-ar-95m-q4_k_m.gguf" | tee "$OUT_DIR/eve-ar-95m-q4_k_m.sha256"
echo "Exported model. Validate Arabic generation and ARM64 phone performance before app integration."
