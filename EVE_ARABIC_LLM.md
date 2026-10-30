# EVE local Arabic language model — compact offline mode

EVE uses a small Arabic instruction-tuned language model to phrase short replies locally. It is not the source of EVE's identity, perception, memory, emotion, curiosity, or planning.

## Selected checkpoint
- Model: \`Abdelkareem/supra-50m-arabic-sft-mix\`
- Parameters: approximately 51.8 million
- Pinned revision: \`279038a25593f6dfce3146585b4d1380b22effe3\`
- Runtime format: GGUF Q4_K_M
- Runtime: \`dev.ffmpegkit-maintained:llama-android:0.1.1\`, llama.cpp CPU-only
- Expected quantized model budget: below 50 MB
- License: Apache-2.0

The model card describes this as an experimental Arabic-only instruction model. Its small size is intended to reduce startup time, memory use, and generation delay on modest devices. It is less capable than a 0.6B–1B model: factual knowledge and multi-step reasoning are weak, and replies can be repetitive or incorrect. The app must not rely on it for important factual decisions.

## Build verification
The workflow downloads the pinned source checkpoint, verifies its source SHA-256, converts it to GGUF, applies a narrow structure-checked ByteLevel tokenizer mapping, and confirms exact tokenizer ID parity for Arabic, punctuation, whitespace and emoji. It quantizes the model to Q4_K_M, generates two short Arabic held-out responses with a 90-second per-prompt limit, then verifies the model checksum inside both APKs.

## Division of responsibilities
- **Language:** the compact Arabic model writes short natural replies.
- **Reasoning and behaviour:** the deterministic EVE brain classifies intent, grounds replies in available app state, selects mood/goal hints, manages curiosity, and avoids repeating the same question.
- **Perception:** camera, face detection, object detection, pose, gestures and facial affect run in separate offline modules.
- **Memory:** personal facts, observations and conversation history remain in local storage. A saved name is not accepted as proof of face identity.
- **Emotions:** EVE's state controller owns mood, intensity, cause and decay; the text model cannot freely alter emotion state.
- **Future body:** movement must use a separate Bluetooth adapter with obstacle and edge-stop interlocks.

## Offline operation and device testing
After installation the bundled model and perception assets are available locally; ordinary dialogue does not need network access. CI success verifies packaging and host-side smoke tests, not actual response speed on a Redmi 12. Physical-device validation is still required for first-load time, tokens per second, RAM use, temperature and Arabic response quality. If native inference fails, EVE falls back to its deterministic local brain.
