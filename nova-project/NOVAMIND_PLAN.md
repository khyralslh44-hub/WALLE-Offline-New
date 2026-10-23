# NovaMind Hybrid — implementation plan

## Current milestone: offline orchestration scaffold

This branch adds `NovaMind.kt` and unit tests. It is **not yet a bundled language model** and is not yet connected to the main activity. The injected `LocalLanguageModel` interface is the seam for an on-device inference runtime. Without an installed model, the code reports that limitation honestly and uses a small deterministic fallback.

## Target architecture for Redmi 12

1. **Conversation model (local):** evaluate a quantized 0.5B–1B instruct model in GGUF format with an Android-compatible `llama.cpp` JNI/runtime integration. Do not select or bundle weights until device RAM, ABI, model license, tokenizer, Arabic quality, and Android runtime compatibility have been checked. The model file is a separate large asset, not source code.
2. **Coordinator:** this Kotlin module bounds user input, prompt size, and output tokens; accepts only an injected local model; has no network client.
3. **Memory:** replace `InMemoryNovaMindMemory` in production with app-private Room/SQLite storage. Persist only explicit, compact facts and coarse topics by default; do not store raw camera frames or full conversation transcripts. Provide a way to inspect and clear saved memory.
4. **Emotion/personality:** use a bounded state vector and event history to influence expression and response style. This is a behavioral simulation, not proof of human-like feelings. Add cooldowns and repeat detection to avoid random or repetitive actions.
5. **Vision:** keep ML Kit results as evidence with confidence. Face detection alone does not identify a person or infer emotion/action. Add specialized on-device models only after evaluating false positives and resource use.
6. **Future robot body:** define a separate Bluetooth transport interface with explicit safety states, sensor freshness checks, motor stop-on-disconnect, and edge/obstacle safeguards. No motor commands should be enabled until hardware and sensors are known.

## Performance acceptance targets (must be measured on the actual phone)

- Fully offline cold start after the model/runtime is installed; airplane-mode test passes.
- Peak memory and thermal behavior remain stable during a 10-minute conversation test.
- First-token latency target: under 5 seconds for short prompts, with graceful fallback if the runtime times out. This is a target, not a measured result.
- Output budget defaults to 120 tokens to control latency and battery.
- No INTERNET permission is added for local inference.
- Arabic prompt suite tests answer relevance, unsupported claims, repetition, and uncertainty handling.
- Camera suite includes people vs. pets, low light, partial faces, and ambiguous scenes; report precision/recall rather than claiming perfect recognition.
- Memory suite verifies restart persistence, no raw image/audio storage, user clear-memory operation, and resistance to incorrect repeated learning.
- Battery/thermal test runs at least 10 minutes; report model, quantization, RAM, CPU, latency, and APK/model sizes.

## Test status

The unit tests in `NovaMindTest.kt` check local-model injection, honest fallback behavior, coarse-topic memory, bounded inputs, and deterministic mood classification. They do **not** test neural language quality, on-device inference speed, or real-device offline behavior. Those require a compatible model runtime and a physical Redmi 12 test.
