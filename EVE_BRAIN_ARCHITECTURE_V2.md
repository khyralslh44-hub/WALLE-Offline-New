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
Supra-50M Arabic SFT Q4_K_M runs locally through llama.cpp. It turns the grounded context into natural Arabic dialogue. It does not process camera frames and does not own identity, mood, memory, or safety decisions. If native inference is unavailable, EVE falls back to the deterministic Arabic companion brain.

## 6. Offline and future body interface
The app ships its model assets and uses local inference; the conversation path has no cloud API. A future Bluetooth movement base should connect through a separate command adapter with obstacle and edge-stop interlocks. Motion commands must be rejected whenever sensor health or distance confidence is insufficient.

## Validation rules
- Confirm the APK contains the exact model checksum.
- Run a bounded Arabic generation smoke test before packaging.
- Test on an ARM64 Android device, not only on an x86 emulator.
- Verify that vision, memory, and the behaviour controller still work when the language model is unavailable.
