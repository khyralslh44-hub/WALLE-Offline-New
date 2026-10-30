#!/usr/bin/env bash
set -euo pipefail

MODEL="eve-project/app/src/main/assets/models/eve-qwen3-0.6b-q4km.gguf"
EXPECTED_SHA256="ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a"
LLAMA_CPP_REVISION="2da668617612d2df773f966e3b0ee22dc2beef7b"
WORK_DIR="$RUNNER_TEMP/eve-qwen3-smoke"
LLAMA_DIR="$WORK_DIR/llama.cpp"
REPORT_DIR="eve-model-build"

mkdir -p "$WORK_DIR" "$REPORT_DIR"
test -s "$MODEL"
test "$(sha256sum "$MODEL" | awk '{print $1}')" = "$EXPECTED_SHA256"

git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git "$LLAMA_DIR"
git -C "$LLAMA_DIR" checkout --detach "$LLAMA_CPP_REVISION"
test "$(git -C "$LLAMA_DIR" rev-parse HEAD)" = "$LLAMA_CPP_REVISION"

cmake -S "$LLAMA_DIR" -B "$LLAMA_DIR/build" \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
  -DLLAMA_BUILD_TESTS=OFF
cmake --build "$LLAMA_DIR/build" --config Release --parallel 2 --target llama-cli

PROMPT=$'<|im_start|>system\nأنت إيف، رفيقة روبوتية عربية ودودة وفضولية. أجيبي بالعربية الطبيعية، باختصار وبدون عرض التفكير الداخلي. لا تختلقي حقائق.\n<|im_end|>\n<|im_start|>user\n/no_think\nعرّفي بنفسك بجملتين قصيرتين، واذكري أنك تعملين محلياً على الهاتف.\n<|im_end|>\n<|im_start|>assistant\n'
OUT="$WORK_DIR/qwen3-output.txt"
ERR="$WORK_DIR/qwen3-stderr.txt"

# Hard cap the smoke test so a slow inference can never stall the whole APK build.
if ! timeout --signal=TERM --kill-after=5s 90s \
  "$LLAMA_DIR/build/bin/llama-cli" \
  -m "$MODEL" -p "$PROMPT" -n 40 -c 1024 -t 2 \
  --temp 0.7 --top-p 0.8 --top-k 20 --repeat-penalty 1.10 \
  --no-display-prompt --simple-io \
  > "$OUT" 2> "$ERR"; then
  echo "FAIL: Qwen3 local inference smoke test failed or exceeded 90 seconds."
  tail -n 60 "$ERR" || true
  exit 30
fi

ARABIC_CHARS="$(grep -oP '[\x{0600}-\x{06FF}]' "$OUT" | wc -l | tr -d ' ')"
if [ "$ARABIC_CHARS" -lt 8 ]; then
  echo "FAIL: Qwen3 smoke test did not produce enough Arabic text."
  cat "$OUT"
  tail -n 40 "$ERR" || true
  exit 31
fi

{
  echo "model_sha256=$EXPECTED_SHA256"
  echo "llama_cpp_revision=$LLAMA_CPP_REVISION"
  echo "prompt=Arabic EVE identity and offline operation"
  echo "arabic_character_count=$ARABIC_CHARS"
  echo "generation_output:"
  cat "$OUT"
} | tee "$REPORT_DIR/qwen3-inference-smoke-test.txt"

echo "PASS: Qwen3 generated Arabic locally through llama.cpp."
