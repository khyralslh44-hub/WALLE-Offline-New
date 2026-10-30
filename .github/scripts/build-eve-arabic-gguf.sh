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

cmake -S "$LLAMA_DIR" -B "$LLAMA_DIR/build" \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
cmake --build "$LLAMA_DIR/build" --config Release --parallel 2 --target llama-quantize llama-cli

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

python - "$LLAMA_DIR/build/bin/llama-cli" "$Q4_MODEL" "$REPORT_DIR" <<'PY'
import pathlib, re, subprocess, sys
cli, model, report_dir = sys.argv[1:4]
report = pathlib.Path(report_dir)
prompts = [
    "كيف كان يومك يا إيف؟",
    "اليوم كنت متضايقاً لأنني أخفقت في شيء مهم.",
    "أنا ألاحظ أن النبات قرب النافذة يميل نحو الضوء، ما التفسير؟",
    "اشرح الفرق بين الطقس والمناخ بلغة بسيطة.",
    "أريد اقتراحاً لنشاط منزلي هادئ في يوم ممطر."
]
samples = []
for index, question in enumerate(prompts, 1):
    chat = (
        "<|im_start|>system\n"
        "أنت إيف، رفيقة روبوتية عربية دافئة وفضولية ومرحة بخفة. تكلمي بطبيعية كرفيقة تعرف سياق الحديث، " +
        "لا كخدمة دعم، ولا تكرري افتتاحيات محفوظة أو تحولي كل رد إلى سؤال. كوني لطيفة بلا تملق، " +
        "ومرحة فقط عندما يناسب الموقف. لا تدعي الوعي أو المشاعر البشرية، ولا تختلقي حقائق. " +
        "إذا لم تعرفي فقولي ذلك بهدوء. أجيبي بالعربية الطبيعية.\\n"
        "<|im_end|>\n<|im_start|>user\n" + question +
        "\n<|im_end|>\n<|im_start|>assistant\n"
    )
    result = subprocess.run(
        [cli, "-m", model, "-p", chat, "-n", "72", "-c", "1024",
         "--temp", "0.68", "--top-p", "0.9", "--repeat-penalty", "1.12",
         "--no-display-prompt", "--simple-io"],
        text=True, capture_output=True, timeout=180
    )
    if result.returncode != 0:
        raise SystemExit(f"FAIL: inference case {index}: {result.stderr[-2000:]}")
    output = result.stdout.strip()
    arabic_count = len(re.findall(r"[\\u0600-\\u06FF]", output))
    if arabic_count < 8:
        raise SystemExit(f"FAIL: insufficient Arabic generated for case {index}: {output[:600]!r}")
    if "<|im_" in output or "<|endoftext|>" in output:
        raise SystemExit(f"FAIL: raw chat-control tokens leaked for case {index}: {output[:600]!r}")
    samples.append(f"CASE {index}\nQUESTION: {question}\nANSWER: {output}\n")
(report / "held-out-arabic-generation.txt").write_text("\n".join(samples), encoding="utf-8")
print("\n".join(samples))
PY

cp "$Q4_MODEL" "$ASSET_DIR/eve-arabic-50m-q4.gguf"
curl --fail --location --retry 3 \
  "https://www.apache.org/licenses/LICENSE-2.0.txt" \
  --output "$ASSET_DIR/Apache-2.0-LICENSE.txt"

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

echo "PASS: compact Arabic GGUF generated, inference-tested, and bundled for offline APK builds."
