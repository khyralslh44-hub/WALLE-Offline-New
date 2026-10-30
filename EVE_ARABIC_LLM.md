# EVE local language brain — Qwen3 0.6B

EVE now uses a multilingual local language model for natural Arabic conversation. It is deliberately separated from the perception, memory, mood, and behaviour systems.

## Selected model

- Base model: Qwen3-0.6B
- GGUF distribution: \`unsloth/Qwen3-0.6B-GGUF\`
- File: \`Qwen3-0.6B-Q4_K_M.gguf\`
- Pinned distribution revision: \`f2d6f9c\`
- SHA-256: \`ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a\`
- Quantization: Q4_K_M
- Approximate download size: 397 MB
- Model license: Apache-2.0
- Runtime: \`dev.ffmpegkit-maintained:llama-android:0.1.1\`, llama.cpp CPU/NEON

The official Qwen3 model family supports more than 100 languages and dialects, instruction following, role-play, and a non-thinking dialogue mode. The 0.6B model is still compact and can make mistakes; it is not equivalent to a large cloud assistant.

## Build flow

The Android workflow:
1. Restores a cached copy of the pinned GGUF when available.
2. Downloads the ready-made GGUF only when it is absent.
3. Verifies the GGUF header, expected size, and SHA-256.
4. Runs a bounded Arabic generation smoke test. This diagnostic is non-blocking so a slow host inference test does not cancel APK packaging.
5. Bundles the exact verified GGUF asset into both APK variants.
6. Checks that both APKs contain the model and that their extracted SHA-256 equals the pinned source hash.

The workflow no longer converts the small Arabic checkpoint or builds a custom tokenizer mapping during the Android build.

## EVE architecture boundaries

- **Language:** Qwen3 generates the natural-language reply in Arabic.
- **Perception:** Camera, face detection, face embeddings, object classification, pose, and gestures remain separate on-device modules.
- **Memory:** Observations, conversation details, and known-person records remain local. A spoken name is not by itself proof of a face identity.
- **Emotion:** The deterministic EVE state controller remains in charge of mood, intensity, and decay. The language model cannot arbitrarily change the emotional state.
- **Curiosity and planning:** The behaviour planner chooses grounded, non-repetitive actions. The language model expresses the selected result; it does not invent sensor evidence or independently drive hardware.
- **Future movement base:** Bluetooth motion control must use a separate safety adapter with obstacle and edge-stop interlocks.

## Offline behaviour

The GGUF is packaged inside the installed APK and is copied into app-private storage on first use. Language generation does not need a network connection after installation. The build server needs internet only to obtain the pinned model and build dependencies.

## Device limitations and validation

A 397 MB model produces a substantially larger APK than the previous 34.62 MB model. The actual APK size must be measured after a successful build. Q4_K_M inference also needs additional working memory beyond the file size; latency, RAM pressure, heating, and response quality must still be checked on the physical Redmi 12. The current build-host smoke test verifies that llama.cpp can load the model and generate Arabic, but it does not prove target-phone speed or perfect reasoning.

The deterministic EVE brain remains available as a local fallback whenever the model asset or native inference runtime cannot be loaded.
