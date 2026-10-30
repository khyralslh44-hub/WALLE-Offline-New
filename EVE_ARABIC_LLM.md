# EVE Arabic generative model

This project now has a reproducible path for a real autoregressive Arabic model rather than only the previous hashed-intent/template engine.

## Selected checkpoint

- Model: `Abdelkareem/supra-50m-arabic-sft-mix`
- Pinned revision: `279038a25593f6dfce3146585b4d1380b22effe3`
- Model card: https://huggingface.co/Abdelkareem/supra-50m-arabic-sft-mix
- License stated by the model card: Apache-2.0
- 51.8M parameters, Llama-style decoder, 32k Arabic BPE tokenizer, ChatML instruction tuning
- Quantized output: GGUF Q4_K_M; CI rejects it if it is 50,000,000 bytes or larger
- Android inference: `dev.ffmpegkit-maintained:llama-android:0.1.1`, llama.cpp, CPU-only ARM64

The checkpoint is downloaded at a pinned revision, checked by SHA-256, converted to GGUF, quantized, smoke-tested with unseen Arabic prompts, and copied into the APK assets by GitHub Actions. Model weights are not committed to this repository.

## Android integration

`EveArabicLlmRuntime` lazily copies the bundled GGUF into app-private files and loads it locally. `LocalLanguageBrain.answerUser` attempts real text generation first and uses `EveTinyArabicBrain` only as an offline fallback if the model asset or native runtime is unavailable. The prompt receives bounded dialogue, local reflections, mood and fresh vision evidence. It explicitly forbids inferring a person's identity from a generic visual label or from a spoken-introduction list.

## Build and evidence

The Android build workflow runs `.github/scripts/build-eve-arabic-gguf.sh` before Gradle. It:
1. downloads the pinned model checkpoint and validates its source-weight hash;
2. checks out a pinned llama.cpp revision;
3. converts the checkpoint to GGUF F16 and quantizes to Q4_K_M;
4. rejects weights above the 50 MB cap;
5. runs three held-out Arabic generation smoke tests using llama.cpp on the build host;
6. packages the quantized model and its Apache-2.0 license into debug and release APKs;
7. verifies model checksums inside the APKs and uploads a model/evaluation report.

## Important limitations

This is a genuinely generative model, but it is only around 50M parameters and Arabic-only. Its model card warns that it can hallucinate and has weak multi-step reasoning; it is not suitable for medical, legal, financial, emergency, or other high-stakes decisions. The host smoke tests prove that the quantized checkpoint can generate Arabic text; they do not prove useful reasoning or target-phone speed. Redmi 12 latency, RAM use, temperature and response quality still require a real-device test.

The app's core inference path does not need internet after installation because the model is bundled into the APK. CI itself downloads model files only while building.
