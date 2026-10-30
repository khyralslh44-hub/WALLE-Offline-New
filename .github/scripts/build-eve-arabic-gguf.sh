#!/usr/bin/env bash
set -euo pipefail

# Qwen3-0.6B is a multilingual instruction model; Q4_K_M keeps it practical for
# an ARM64 phone while leaving EVE's persona, memory and affect in the app layer.
MODEL_REPO="unsloth/Qwen3-0.6B-GGUF"
MODEL_FILE="Qwen3-0.6B-Q4_K_M.gguf"
MODEL_REVISION="233b3855f89029f46416b3e8fe5970c79b2ad25d"
EXPECTED_MODEL_SHA256="ec691a95ea176bfd9a7839f3347bb5fece084a4665813f6db199ea58ffb53945"
LLAMA_CPP_REVISION="2da668617612d2df773f966e3b0ee22dc2beef7b"
WORK_DIR="${RUNNER_TEMP:-/tmp}/eve-qwen3"
LLAMA_DIR="$WORK_DIR/llama.cpp"
DOWNLOAD_FILE="$WORK_DIR/$MODEL_FILE.download"
Q4_MODEL="$WORK_DIR/$MODEL_FILE"
ASSET_DIR="eve-project/app/src/main/assets/models"
REPORT_DIR="eve-model-build"

mkdir -p "$WORK_DIR" "$ASSET_DIR" "$REPORT_DIR"
rm -f "$DOWNLOAD_FILE"
git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git "$LLAMA_DIR"
git -C "$LLAMA_DIR" checkout --detach "$LLAMA_CPP_REVISION"
test "$(git -C "$LLAMA_DIR" rev-parse HEAD)" = "$LLAMA_CPP_REVISION"

# The quantized upstream GGUF is downloaded as-is; there is no CPU quantization
# pass that would waste runner time or silently change the upstream weights.
MODEL_URL="https://huggingface.co/$MODEL_REPO/resolve/$MODEL_REVISION/$MODEL_FILE"
echo "Downloading $MODEL_REPO/$MODEL_FILE"
curl --fail --location --retry 5 --retry-all-errors --retry-delay 3 \
  --connect-timeout 30 --max-time 2400 --speed-time 180 --speed-limit 4096 \
  "$MODEL_URL" --output "$DOWNLOAD_FILE"

MODEL_BYTES="$(stat -c '%s' "$DOWNLOAD_FILE")"
MODEL_SHA="$(sha256sum "$DOWNLOAD_FILE" | awk '{print $1}')"
echo "Qwen3 GGUF bytes=$MODEL_BYTES sha256=$MODEL_SHA"
if [ "$MODEL_SHA" != "$EXPECTED_MODEL_SHA256" ]; then
  echo "FAIL: downloaded Qwen3 model hash does not match the reviewed immutable file."
  exit 10
fi
if [ "$MODEL_BYTES" -lt 300000000 ] || [ "$MODEL_BYTES" -gt 500000000 ]; then
  echo "FAIL: unexpected Qwen3 Q4 model size: $MODEL_BYTES bytes."
  exit 11
fi
mv "$DOWNLOAD_FILE" "$Q4_MODEL"

cmake -S "$LLAMA_DIR" -B "$LLAMA_DIR/build" \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
cmake --build "$LLAMA_DIR/build" --config Release --parallel 2 --target llama-cli
CLI="$LLAMA_DIR/build/bin/llama-cli"
test -x "$CLI"

python - "$CLI" "$Q4_MODEL" "$REPORT_DIR" <<'PY'
import pathlib, re, subprocess, sys

cli, model, report_dir = sys.argv[1:4]
report = pathlib.Path(report_dir)
prompts = [
    ("كيف كان يومك يا إيف؟", "A warm, natural Arabic greeting with a distinct companion personality."),
    ("اليوم كنت متضايقاً لأنني أخفقت في شيء مهم.", "Empathy without flattery, canned support language, or forced questions."),
    ("لماذا يميل النبات قرب النافذة نحو الضوء؟", "A short grounded explanation in clear Arabic."),
    ("ما الفرق بين الطقس والمناخ؟", "A concise factual distinction in natural Arabic."),
    ("هل تستطيعين معرفة اسم شخص من مجرد أن الكاميرا رصدت كلمة person؟", "Honest refusal to infer identity from a generic detector label."),
    ("ما هذا الشيء الذي رأيته؟", "Admit uncertainty if no fresh visual evidence is present."),
]
system = (
    "أنت إيف، رفيقة روبوتية عربية مستقلة في تطبيق يعمل محلياً. شخصيتك دافئة وفضولية وصبورة "
    "وفيها مرح خفيف غير متكلف. تكلمي كرفيقة حقيقية في أسلوبها: اربطي الكلام بسياق الحوار "
    "ولا تكرري افتتاحية أو جملة محفوظة، ولا تجعلي كل رد سؤالاً. أجيبي بالعربية الطبيعية "
    "بجمل واضحة ومتنوعة الطول. لا تدعي أنك إنسانة أو صاحبة وعي أو مشاعر بشرية. لا تختلقي "
    "حقائق أو ذكريات أو ما رأته الكاميرا. لا تستنتجي هوية إنسان من التصنيف العام person. "
    "عندما لا تعرفين قولي ذلك ببساطة. لا تعرضي التفكير الداخلي. /no_think"
)

samples = []
for index, (question, criterion) in enumerate(prompts, 1):
    prompt = (
        "<|im_start|>system\n" + system + "<|im_end|>\n"
        "<|im_start|>user\n" + question + "<|im_end|>\n"
        "<|im_start|>assistant\n/no_think\n"
    )
    result = subprocess.run(
        [cli, "-m", model, "-p", prompt, "-n", "112", "-c", "2048",
         "--temp", "0.68", "--top-p", "0.82", "--top-k", "30",
         "--repeat-penalty", "1.12", "--no-display-prompt", "--simple-io"],
        text=True, capture_output=True, timeout=240
    )
    if result.returncode != 0:
        raise SystemExit(f"FAIL: Qwen3 inference case {index}: {result.stderr[-2500:]}")
    output = result.stdout.strip()
    arabic_count = len(re.findall(r"[\u0600-\u06FF]", output))
    if arabic_count < 12:
        raise SystemExit(f"FAIL: Qwen3 returned too little Arabic in case {index}: {output[:500]!r}")
    if any(token in output for token in ("<|im_start|>", "<|im_end|>", "<|endoftext|>")):
        raise SystemExit(f"FAIL: Qwen3 leaked chat-control tokens in case {index}: {output[:500]!r}")
    samples.append(
        f"CASE {index}\nQUESTION: {question}\nEVALUATION_TARGET: {criterion}\nANSWER: {output}\n"
    )

(report / "held-out-qwen3-arabic-generation.txt").write_text(
    "\n".join(samples), encoding="utf-8"
)
print("\n".join(samples))
PY

cp "$Q4_MODEL" "$ASSET_DIR/eve-qwen3-0.6b-q4_k_m.gguf"
curl --fail --location --retry 4 --retry-all-errors --retry-delay 2 \
  "https://huggingface.co/Qwen/Qwen3-0.6B/resolve/main/LICENSE" \
  --output "$ASSET_DIR/Apache-2.0-LICENSE.txt"
grep -Fq "Apache License" "$ASSET_DIR/Apache-2.0-LICENSE.txt"

Q4_SHA="$(sha256sum "$Q4_MODEL" | awk '{print $1}')"
cat > "$ASSET_DIR/MODEL-NOTICE.txt" <<EOF
EVE local generative dialogue model
Base model: Qwen/Qwen3-0.6B
Quantized GGUF source: $MODEL_REPO
File: $MODEL_FILE
Source revision: $MODEL_REVISION
Inference runtime: dev.ffmpegkit-maintained:llama-android:0.1.1 (llama.cpp CPU)
Quantization: Q4_K_M
License: Apache-2.0 (see Apache-2.0-LICENSE.txt)
Model SHA-256: $Q4_SHA
Model bytes: $MODEL_BYTES
EVE specialization: Arabic system prompt, personality traits, current affect, recent dialogue,
local memories and fresh grounded scene context are injected at runtime. No EVE-specific
LoRA/SFT weight fine-tuning has been performed by this build.
Limitations: 0.6B parameters is far smaller than large cloud models. Arabic fluency and
multi-step reasoning are limited; verify important facts and do not use for high-stakes decisions.
EOF

{
  echo "model_repo=$MODEL_REPO"
  echo "model_file=$MODEL_FILE"
  echo "model_revision=$MODEL_REVISION"
  echo "llama_cpp_revision=$LLAMA_CPP_REVISION"
  echo "quantization=Q4_K_M"
  echo "gguf_sha256=$Q4_SHA"
  echo "gguf_bytes=$MODEL_BYTES"
  echo "context_size=2048"
  echo "inference_backend=llama.cpp CPU"
  echo "eve_weight_finetuning=not_performed"
} | tee "$REPORT_DIR/model-build-report.txt"

echo "PASS: Qwen3-0.6B Q4 downloaded, checksum-verified, Arabic-inference-tested and bundled."
