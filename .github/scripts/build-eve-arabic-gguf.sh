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

# Preserve tokenizer diagnostics in the CI log/report. Do not guess a tokenizer
# family: an incorrect pre-tokenizer mapping can silently corrupt Arabic prompts.
python - "$MODEL_DIR" "$REPORT_DIR" <<'PY'
import json, pathlib, sys
from transformers import AutoTokenizer
model_dir, report_dir = map(pathlib.Path, sys.argv[1:3])
cfg = json.loads((model_dir / "tokenizer.json").read_text(encoding="utf-8"))
tokenizer = AutoTokenizer.from_pretrained(str(model_dir))
probe = "\\n \\n\\n 3 33 3333 🚀 العربيةُ، كيف حالك؟ "
ids = tokenizer.encode(probe)
diagnostic = {
    "model_type": json.loads((model_dir / "config.json").read_text(encoding="utf-8")).get("model_type"),
    "tokenizer_class": json.loads((model_dir / "tokenizer_config.json").read_text(encoding="utf-8")).get("tokenizer_class"),
    "vocab_size": len(tokenizer),
    "normalizer": cfg.get("normalizer"),
    "pre_tokenizer": cfg.get("pre_tokenizer"),
    "decoder": cfg.get("decoder"),
    "probe_ids": ids,
    "probe_decoded": tokenizer.decode(ids),
}
path = report_dir / "tokenizer-diagnostics.json"
path.write_text(json.dumps(diagnostic, ensure_ascii=False, indent=2), encoding="utf-8")
print("EVE_TOKENIZER_DIAGNOSTICS_BEGIN")
print(json.dumps(diagnostic, ensure_ascii=False, indent=2))
print("EVE_TOKENIZER_DIAGNOSTICS_END")
PY

MODEL_SHA="$(sha256sum "$MODEL_DIR/model.safetensors" | awk '{print $1}')"
EXPECTED_MODEL_SHA="9c3bf4b309c65fbdd875af44e6b5fbe034129779ca7be3ab419ec6bddb39e295"
if [ "$MODEL_SHA" != "$EXPECTED_MODEL_SHA" ]; then
  echo "FAIL: Hugging Face checkpoint hash does not match the pinned revision."
  exit 10
fi

cmake -S "$LLAMA_DIR" -B "$LLAMA_DIR/build" \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
cmake --build "$LLAMA_DIR/build" --config Release --parallel 2 --target llama-quantize llama-cli llama-tokenize

# The pinned upstream converter recognizes BPE pre-tokenizers by a vocabulary-
# dependent hash. This checkpoint has its own Arabic vocabulary, so the hash is
# necessarily new even though its pre-tokenization is standard GPT-2 ByteLevel.
# Add a narrow, structure-validated mapping instead of guessing from the hash.
python - "$LLAMA_DIR/conversion/base.py" <<'PY'
import pathlib, sys

base_path = pathlib.Path(sys.argv[1])
source = base_path.read_text(encoding="utf-8")
marker = '        if res is None:\n            logger.warning("\\n")'
if source.count(marker) != 1:
    raise SystemExit("FAIL: pinned llama.cpp converter structure changed; refusing an unsafe patch.")

compatibility = '''        if res is None:
            tokenizer_path = self.dir_model / "tokenizer.json"
            if tokenizer_path.is_file():
                with tokenizer_path.open("r", encoding="utf-8") as tokenizer_file:
                    tokenizer_json = json.load(tokenizer_file)
                tokenizer_model = tokenizer_json.get("model") or {}
                pre_tokenizer = tokenizer_json.get("pre_tokenizer") or {}
                decoder = tokenizer_json.get("decoder") or {}
                tokenizer_config_path = self.dir_model / "tokenizer_config.json"
                tokenizer_config = {}
                if tokenizer_config_path.is_file():
                    with tokenizer_config_path.open("r", encoding="utf-8") as config_file:
                        tokenizer_config = json.load(config_file)
                # ByteLevel(use_regex=True, add_prefix_space=False) uses the
                # same GPT-2 split regex and byte-to-unicode mapping implemented
                # by llama.cpp's "gpt-2" pre-tokenizer. This does NOT reuse a
                # different model's vocabulary: get_vocab_base() and the
                # tokenizer.json merges below remain the checkpoint's own.
                validated_bytelevel_bpe = (
                    tokenizer_model.get("type") == "BPE"
                    and isinstance(tokenizer_model.get("merges"), list)
                    and len(tokenizer_model["merges"]) > 0
                    and not tokenizer_model.get("byte_fallback", False)
                    and tokenizer_json.get("normalizer") is None
                    and pre_tokenizer == {
                        "type": "ByteLevel",
                        "add_prefix_space": False,
                        "trim_offsets": True,
                        "use_regex": True,
                    }
                    and decoder.get("type") == "ByteLevel"
                    and tokenizer_config.get("add_prefix_space", False) is not True
                )
                if validated_bytelevel_bpe:
                    res = "gpt-2"
                    logger.info(
                        "Detected structurally validated custom ByteLevel BPE; "
                        "using llama.cpp GPT-2 pre-tokenizer with the checkpoint's own vocab and merges."
                    )
'''
source = source.replace(marker, compatibility + marker, 1)
base_path.write_text(source, encoding="utf-8")
print("PASS: narrow ByteLevel-BPE compatibility mapping installed.")
PY

python "$LLAMA_DIR/convert_hf_to_gguf.py" "$MODEL_DIR" \
  --outfile "$F16_MODEL" --outtype f16 \
  --model-name "EVE-Supra-50M-Arabic-SFT"
test -s "$F16_MODEL"

# Prove that llama.cpp token IDs match the original Arabic tokenizer. This catches
# an incorrect pre-tokenizer label before quantization or APK packaging.
python - "$MODEL_DIR" "$F16_MODEL" "$LLAMA_DIR/build/bin/llama-tokenize" "$REPORT_DIR" <<'PY'
import ast, pathlib, subprocess, sys, tempfile
from transformers import AutoTokenizer

model_dir, gguf_model, llama_tokenize, report_dir = map(pathlib.Path, sys.argv[1:5])
tokenizer = AutoTokenizer.from_pretrained(str(model_dir))
cases = [
    "السلام عليكم، كيف حالك يا إيف؟",
    "الطقسُ ممطرٌ اليوم؛ هل نخرج؟ 12345",
    "  مسافتان\tوسطر\nثانٍ ",
    "🚀 أهلاً بالعالم! EVE",
]
evidence = []
with tempfile.TemporaryDirectory(prefix="eve-tokenizer-parity-") as tmp:
    for index, text in enumerate(cases, 1):
        prompt_file = pathlib.Path(tmp) / f"case-{index}.txt"
        prompt_file.write_text(text, encoding="utf-8")
        expected = tokenizer.encode(text, add_special_tokens=False)
        result = subprocess.run(
            [str(llama_tokenize), "-m", str(gguf_model), "-f", str(prompt_file),
             "--ids", "--no-bos", "--no-escape", "--no-parse-special", "--log-disable"],
            text=True, capture_output=True, timeout=60
        )
        if result.returncode != 0:
            raise SystemExit(f"FAIL: llama-tokenize case {index}: {result.stderr[-1600:]}")
        try:
            actual = ast.literal_eval(result.stdout.strip().splitlines()[-1])
        except Exception as exc:
            raise SystemExit(f"FAIL: could not parse llama.cpp token IDs for case {index}: {result.stdout!r}") from exc
        if expected != actual:
            raise SystemExit(
                f"FAIL: tokenizer parity case {index}: HF={expected}, llama.cpp={actual}"
            )
        evidence.append(
            f"CASE {index}: exact token-ID parity ({len(expected)} tokens) | {text!r}"
        )
pathlib.Path(report_dir, "tokenizer-parity.txt").write_text(
    "\n".join(evidence) + "\n", encoding="utf-8"
)
print("\n".join(evidence))
print("PASS: Arabic tokenizer token IDs exactly match in Hugging Face and llama.cpp.")
PY

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
        "إذا لم تعرفي فقولي ذلك بهدوء. أجيبي بالعربية الطبيعية.\n"
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
    arabic_count = len(re.findall(r"[\u0600-\u06FF]", output))
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
