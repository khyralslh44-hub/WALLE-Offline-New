# EVE Compact Arabic Brain

## Goal

EVE's everyday Arabic understanding and reply composition are implemented specifically for this project. The Android conversation path does not package or invoke a pretrained generative language model.

## How it works

1. **Arabic normalization:** removes optional diacritics and normalizes common alef/hamza and spelling variants before comparison.
2. **Compact understanding:** represents an utterance with hashed word, character n-gram, and adjacent-word features. Small intent prototypes classify supported conversational meanings without a neural checkpoint or external tokenizer.
3. **Context grounding:** the response engine receives bounded dialogue history, EVE's current behavior state, saved local notes, and only fresh visual evidence. A generic visual label is never treated as a person's identity.
4. **Semantic response composition:** EVE extracts a speech act (why/how/what/yes-no/request), a topic, user-state cues, and short follow-up references. A compact Arabic grammar realizer builds clauses from those semantic slots and grounded local evidence such as recent observations, scene summaries, mood reason, and saved concepts. It does not select a complete answer from a bank of finished sentences.
5. **Honest uncertainty:** unsupported general-knowledge questions are not forced into a nearby intent. EVE asks the user to clarify or states that it does not know.

## Package and runtime

- Kotlin implementation: `EveTinyArabicBrain.kt` and `LocalLanguageBrain.kt`.
- No GGUF/Safetensors/PyTorch language checkpoint is downloaded or packaged.
- No llama.cpp dependency or llama native library is required.
- No language-model network call exists in the dialogue path.
- Runtime state and conversational processing remain on the Android device.

The engine is designed to be small and deterministic. Its size is mainly Kotlin code and compact feature structures, not hundreds of megabytes of language weights. Its response realizer is a bounded rule-based composer: it can combine meaning and context for the supported companion domain, but it is not unrestricted open-domain text generation.

## What “custom” and “learning” mean here

The interpretation features, intent prototypes, response rules, context handling, and EVE-specific voice are authored for this project. This is not a claim that a foundation model was trained from scratch. Local memory can save user-confirmed facts and associations, but that memory does not update neural-network weights. Future training of a tiny statistical language model would require a curated Arabic dataset, a reproducible training pipeline, evaluation data, and measured on-device inference before it could replace the current engine.

## Scope and limitations

This engine is intended for supported everyday companion dialogue and EVE-specific interactions, not unrestricted factual question answering. A general-purpose LLM can understand and answer a much wider range of questions; removing it intentionally trades that breadth for a much smaller and more predictable on-device implementation. The supported intent and language coverage must grow through tests and reviewed examples.

Arabic speech recognition (converting microphone audio into text) is separate from this language engine. The current Vosk-based speech-to-text component is a separately bundled pretrained acoustic/language model; it is not the Arabic dialogue generator. Its size and accuracy should be measured independently rather than hidden inside the language-engine size claim.

## Required verification

The offline Android workflow must:
- run the unit and instrumentation tests for the bespoke Arabic engine;
- run with Wi-Fi and mobile data disabled;
- check the APK contains no GGUF/Safetensors/PyTorch language weights;
- check the APK contains no llama.cpp native libraries;
- confirm the Android brain returns Arabic replies for tested supported utterances and rejects an unsupported factual question without inventing an answer.

Passing these checks proves the compact engine runs locally for the tested cases. It does not prove human-level Arabic understanding, open-domain knowledge, or successful operation on every phone without a real-device test.
