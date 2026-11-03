# EVE Brain Architecture — Local Companion v2

EVE is designed as a local companion system, not as a chat screen with a model behind it.

## 1. Perception layer
Camera, face detection, face embeddings, object classification, pose, and gesture models run on-device. Each observation carries a timestamp and confidence. A generic object label is treated as a hypothesis, not proof of identity or of an activity.

## 2. Memory layer
Local memory stores observations, repeated sightings, user-provided facts, and dialogue context as separate record types. A remembered name is not automatically attached to a face; identity requires a local face-match result above a conservative threshold.

## 3. Emotional state layer
A deterministic state controller owns the current mood, its intensity, causes, and decay. The language model cannot arbitrarily set EVE's emotional state. Mood changes must be grounded in an interaction, observation, or remembered event.

## 4. Curiosity and planning layer
A behaviour planner selects bounded actions from current evidence and recent history. It suppresses repeated questions, avoids random actions, and asks for clarification when confidence is weak. Internal reflections stay internal; only a useful, context-aware question is shown to the user.

## 5. Language layer
EVE uses a bespoke compact Arabic dialogue engine written for this project. It normalizes Arabic spelling, classifies utterances with a small hashed character n-gram/lexical prototype model, extracts bounded conversational cues, and composes grounded Arabic replies from EVE-specific language patterns and live context. The engine has no pretrained generative checkpoint, tokenizer package, llama.cpp runtime, or model-weight download. It runs locally in Kotlin on the phone.

This is a deliberately small domain-focused engine, not a general-purpose LLM: it can handle supported everyday dialogue, EVE's capabilities, vision context, local memory, mood, and game interaction. It must acknowledge questions outside its supported knowledge instead of inventing an answer. The engine's local learning stores user-confirmed associations and context; it does not silently retrain neural weights. Arabic speech-to-text is a separate component and must be evaluated separately from language understanding and reply composition.

## 6. Offline and future body interface
The app ships its model assets and uses local inference; the conversation path has no cloud API. A future Bluetooth movement base should connect through a separate command adapter with obstacle and edge-stop interlocks. Motion commands must be rejected whenever sensor health or distance confidence is insufficient.

## Validation rules
- Confirm the APK contains the exact model checksum.
- Run a bounded Arabic generation smoke test before packaging.
- Test on an ARM64 Android device, not only on an x86 emulator.
- Verify that vision, memory, and the behaviour controller still work when the language model is unavailable.
