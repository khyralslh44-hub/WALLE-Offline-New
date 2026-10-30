# EVE compact Arabic AI and speech models

This branch replaces the very small Arabic-only 50M text model and the 318 MB Vosk Arabic archive with a more capable multilingual generator and a much smaller streaming Arabic recognizer.

## 1. Text generation: Qwen3-0.6B Q4_K_M

- Base model: [Qwen3-0.6B](https://huggingface.co/Qwen/Qwen3-0.6B)
- Quantized GGUF source: [unsloth/Qwen3-0.6B-GGUF](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF)
- File: `Qwen3-0.6B-Q4_K_M.gguf`
- SHA-256 pinned in CI: `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a`
- License: Apache-2.0
- Expected file size: approximately 397 MB (CI checks the exact hash and a 300–500 MB safety range)
- Runtime: llama.cpp through `dev.ffmpegkit-maintained:llama-android:0.1.1`, CPU-only
- Context configured for the phone build: 2,048 tokens; 2–4 CPU threads; Q4 weights

Qwen3 supports more than 100 languages, including Arabic. The Q4 file is much smaller than large 7B/8B-class models but is still too large to describe as a tiny model. On a Redmi 12 it may be slower than on a flagship; speed and sustained temperature must be measured on the actual phone.

### How EVE is specialized

The model is conditioned at runtime through `EveArabicPromptBuilder` and `LocalLlmContext`:
- consistent EVE character traits (warmth, curiosity, playfulness and patience);
- current mood and why it changed;
- recent conversation, reflections and saved observations;
- only fresh vision evidence, with explicit uncertainty and identity safeguards;
- instructions to answer naturally in Arabic, avoid canned openings and not expose internal reasoning.

The deterministic affect/behavior engine remains in charge of mood and action hints; Qwen writes the natural-language response. The model weights have **not** yet been fine-tuned with an EVE LoRA/SFT adapter. This is prompt-and-memory specialization, not a claim that Qwen has been trained into a new foundation model. A later LoRA can use a reviewed EVE dialogue dataset and must be evaluated before being merged.

### Build and tests

`.github/scripts/build-eve-arabic-gguf.sh` downloads the quantized file, verifies its SHA-256, builds a pinned llama.cpp CLI, and runs held-out Arabic prompts for greetings, empathy, everyday explanation, visual uncertainty and identity safety. It then places the GGUF and Apache license in APK assets. CI also verifies the same model checksum inside both debug and release APKs.

## 2. Arabic speech recognition: Moonshine Tiny Streaming

- Runtime: `ai.moonshine:moonshine-voice:0.1.5`
- Language: Arabic
- Architecture: Tiny Streaming
- Parameters: 34 million
- Reported Arabic WER: 15.5% on the project's 400-clip Common Voice/FLEURS evaluation panel
- License: MIT for the current streaming model
- Assets: ORT model files plus `tokenizer.bin`, fetched from the official Moonshine model catalog and checksum-validated by its downloader
- App path: `assets/moonshine/ar/`
- Inference: entirely local; CI bundles every needed file into the APK, and Android loads it through `MicTranscriber.modelsFrom()`.

The previous Vosk Arabic MGB-2 archive was about 318 MB compressed. Moonshine Tiny Streaming has 34M parameters and the new build script rejects a model bundle larger than 100 MB. The actual number of downloaded bytes is recorded by CI.

This is **speech-to-text** (hearing the user), not a spoken Arabic TTS voice. EVE's existing original electronic nonverbal voice cues remain in `RobotVoice.kt`; they are generated locally and are not a cloned actor or movie recording.

## 3. Offline and device limits

- After installation, the bundled text and speech models do not require an internet connection.
- The debug APK may be large because the Qwen3 Q4 file is about 397 MB; the speech model is separate and much smaller.
- Qwen3-0.6B is materially more capable than a 50M classifier/template model, but it does **not** match large cloud models in reasoning, knowledge or reliability.
- CI held-out generation proves that the model loads and generates Arabic on the build runner. It does not prove acceptable latency, RAM use, heat, or conversation quality on a Redmi 12.
- The final quality check still needs a real-device run with Wi-Fi/mobile data disabled. Important factual claims must remain verifiable; the model can hallucinate.
