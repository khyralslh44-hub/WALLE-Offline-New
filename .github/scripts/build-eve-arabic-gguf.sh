#!/usr/bin/env bash
set -euo pipefail

MODEL_ID="Abdelkareem/supra-50m-arabic-sft-mix"
MODEL_REVISION="279038a25593f6dfce3146585b4d1380b22effe3"
LLAMA_CPP_REVISION="2da668617612d2df773f966e3b0ee22dc2beef7b"
WORK_DIR="${RUNNER_TEMP:-/tmp}/eve-arabic-llm"
MODEL_DIR="$WORK_DIR/checkpoint"
LLAMA_DIR="$WORK_DIR/llama.cpp"
F16_MODEL="$WORK_DIR/eve-arabic-50m-f16.gguf"
Q4_MODEL="$WORK_DIR/eve-arabic-50m-q4.gguf"
ASSET_DIR="eve-project/app/src/main/assets/models"
REPORT_DIR="eve-model-build"

mkdir -p "$WORK_DIR" "$ASSET_DIR" "$REPORT_DIR"
python -m pip install --upgrade pip
python -m pip install huggingface_hub numpy sentencepiece protobuf safetensors transformers
python -m pip install --index-url https://download.pytorch.org/whl/cpu torch==2.11.0

git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git "$LLAMA_DIR"
git -C "$LLAMA_DIR" checkout --detach "$LLAMA_CPP_REVISION"
test "$(git -C "$LLAMA_DIR" rev-parse HEAD)" = "$LLAMA_CPP_REVISION"


python - <<'PY'
import os
from huggingface_hub import snapshot_download
snapshot_download(
    repo_id="Abdelkareem/supra-50m-arabic-sft-mix",
    revision="279038a25593f6dfce3146585b4d1380b22effe3",
    local_dir=os.path.join(os.environ["RUNNER_TEMP"], "eve-arabic-llm", "checkpoint"),
    allow_patterns=[
        "config.json", "generation_config.json", "tokenizer.json",
        "tokenizer_config.json", "chat_template.jinja",
        "model.safetensors", "README.md"
    ],
)
PY

test -s "$MODEL_DIR/config.json"
test -s "$MODEL_DIR/tokenizer.json"
test -s "$MODEL_DIR/model.safetensors"
MODEL_SHA="$(sha256sum "$MODEL_DIR/model.safetensors" | awk '{print $1}')"
EXPECTED_MODEL_SHA="9c3bf4b309c65fbdd875af44e6b5fbe034129779ca7be3ab419ec6bddb39e295"
if [ "$MODEL_SHA" != "$EXPECTED_MODEL_SHA" ]; then
  echo "FAIL: Hugging Face checkpoint hash does not match the pinned revision."
  exit 10
fi

# Register this checkpoint's observed tokenizer fingerprint only after verifying
# tokenizer.json declares the canonical ByteLevel+BPE pre-tokenizer. The vocab is
# custom; "gpt-2" here identifies the ByteLevel regex behavior, not its vocabulary.
python - "$MODEL_DIR/tokenizer.json" "$LLAMA_DIR/conversion/base.py" <<'PY'
import json
import pathlib
import sys

tokenizer_path = pathlib.Path(sys.argv[1])
converter_path = pathlib.Path(sys.argv[2])
tok = json.loads(tokenizer_path.read_text(encoding="utf-8"))
expected_pre = {
    "type": "ByteLevel",
    "add_prefix_space": False,
    "trim_offsets": True,
    "use_regex": True,
}
if tok.get("model", {}).get("type") != "BPE":
    raise SystemExit("FAIL: expected the checkpoint's BPE tokenizer.")
if tok.get("pre_tokenizer") != expected_pre:
    raise SystemExit(
        "FAIL: tokenizer pre-tokenizer changed; refusing to label it GPT-2 ByteLevel."
    )

source = converter_path.read_text(encoding="utf-8")
known = '''        if chkhsh == "3ce83efda5659b07b1ad37ca97ca5797ea4285d9b9ab0dc679e4a720c9da7454":
            # ref: https://huggingface.co/openai-community/gpt2
            res = "gpt-2"
'''
custom_hash = "45782d312d8b8388a4a5d4b89e2984f81ed9e9336dd5f97c6b805edafe1be9a8"
custom = f'''        if chkhsh == "{custom_hash}":
            # Supra Arabic uses the same verified ByteLevel+regex pre-tokenizer.
            res = "gpt-2"
'''
if custom_hash in source:
    raise SystemExit("FAIL: custom tokenizer fingerprint is already present; inspect duplicate.")
if known not in source:
    raise SystemExit("FAIL: pinned llama.cpp converter anchor changed; refusing a blind patch.")
converter_path.write_text(source.replace(known, known + custom, 1), encoding="utf-8")
print("PASS: registered verified Supra Arabic ByteLevel BPE fingerprint.")
PY

cmake -S "$LLAMA_DIR" -B "$LLAMA_DIR/build" \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
cmake --build "$LLAMA_DIR/build" --config Release --parallel 2 --target llama-quantize

python "$LLAMA_DIR/convert_hf_to_gguf.py" "$MODEL_DIR" \
  --outfile "$F16_MODEL" --outtype f16 \
  --model-name "EVE-Supra-50M-Arabic-SFT"
test -s "$F16_MODEL"
"$LLAMA_DIR/build/bin/llama-quantize" "$F16_MODEL" "$Q4_MODEL" Q4_K_M
test -s "$Q4_MODEL"

MODEL_BYTES="$(stat -c '%s' "$Q4_MODEL")"
MAX_MODEL_BYTES=50000000
if [ "$MODEL_BYTES" -ge "$MAX_MODEL_BYTES" ]; then
  echo "FAIL: Q4 GGUF is $MODEL_BYTES bytes, exceeding the strict 50 MB limit."
  exit 11
fi

# Bundle the verified quantized model before running an optional inference smoke test.
# Slow hosted CPU inference must not prevent producing an installable APK.
cp "$Q4_MODEL" "$ASSET_DIR/eve-arabic-50m-q4.gguf"
curl --fail --location --retry 3 \
  "https://www.apache.org/licenses/LICENSE-2.0.txt" \
  --output "$ASSET_DIR/Apache-2.0-LICENSE.txt"

INFERENCE_STATUS="NOT_RUN_BUILD_STAGE"
cat > "$REPORT_DIR/inference-smoke-test.txt" <<'EOF'
status=NOT_RUN_BUILD_STAGE
reason=Arabic model was converted, quantized, checksum-verified, and bundled.
The hosted build skips generative inference to avoid blocking APK creation.
Run on-device Arabic generation verification after installing the APK.
EOF

Q4_SHA="$(sha256sum "$Q4_MODEL" | awk '{print $1}')"
cat > "$ASSET_DIR/MODEL-NOTICE.txt" <<EOF
EVE Arabic generative model
Checkpoint: $MODEL_ID
Pinned revision: $MODEL_REVISION
Format: GGUF Q4_K_M
Inference runtime: dev.ffmpegkit-maintained:llama-android:0.1.1
Checkpoint license: Apache-2.0 (see Apache-2.0-LICENSE.txt)
Original model card: https://huggingface.co/$MODEL_ID
Original checkpoint SHA-256: $MODEL_SHA
Quantized GGUF SHA-256: $Q4_SHA
Quantized GGUF bytes: $MODEL_BYTES
Limitations: Arabic-only, 50M-scale instruction model; it may generate incorrect facts,
has weak multi-step reasoning, and is not suitable for high-stakes decisions.
EOF

{
  echo "model_id=$MODEL_ID"
  echo "model_revision=$MODEL_REVISION"
  echo "llama_cpp_revision=$LLAMA_CPP_REVISION"
  echo "source_safetensors_sha256=$MODEL_SHA"
  echo "quantization=Q4_K_M"
  echo "gguf_sha256=$Q4_SHA"
  echo "gguf_bytes=$MODEL_BYTES"
  echo "limit_bytes=$MAX_MODEL_BYTES"
} | tee "$REPORT_DIR/model-build-report.txt"

echo "PASS: compact Arabic GGUF converted, size-checked, hash-recorded, and bundled for offline APK builds. Inference smoke test: $INFERENCE_STATUS."
