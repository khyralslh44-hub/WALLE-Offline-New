# NovaMind Hybrid — from-scratch model milestone

## What is implemented in this branch

- `NovaOwnModel.kt` contains a small multi-layer perceptron (192 hashed text features, a 20-unit hidden layer, and 7 intent classes). Its weights start from deterministic random initialization; it trains locally from the source-controlled Arabic seed examples. It uses no pretrained weights, external model library, cloud API, or network download.
- The model classifies seven broad intents: greeting, question, vision query, memory request, positive feedback, negative feedback, and farewell. It reports confidence and can return UNKNOWN for blank/low-confidence inputs.
- `NovaMind.kt` uses the classifier to select a coarse response style and simulated behavior state. Explicit corrections can update the weights. A compact versioned weight snapshot can be stored via the supplied `NovaMindMemory` interface and restored on the next initialization.
- The unit-test source covers common Arabic examples, snapshot round-trip, correction updates, bounded fallback behavior, and honest capability boundaries.

## Important limitations

This model is **not** a general-purpose language generator. It does not understand arbitrary Arabic text at ChatGPT level and does not generate open-ended answers. The short responses are authored templates selected by the neural classifier. The seed examples are a small, manually written starter dataset; robust generalization will require a larger reviewed dataset and measured error analysis.

`NovaActivity` now connects `NovaMind` to a long-press text dialog, persists the local model snapshot/correction counters in app-private `SharedPreferences`, and connects the affect state to visible expressions and synthesized local sound cues. A user-accessible memory reset clears local counters, saved intent-model corrections, and affect state. It does not store full chat transcripts or raw camera frames. The existing `NovaBrain` still handles tap/idle behavior separately. GitHub Actions passing proves compilation and unit tests only; real Redmi 12 performance and airplane-mode behavior remain unmeasured.

## Next implementation steps

1. Verify the latest GitHub Actions build, unit tests, APK signing, and no-network/no-microphone manifest checks.
2. Test camera permission, front/back swipe switching, memory reset, expressions, and synthesized audio on the actual Redmi 12; these cannot be certified from a CI build alone.
3. Add a real offline face-embedding model and validated enrollment/matching pipeline before claiming family/friend identity recognition. The Nova project currently contains no `MobileFaceNet.tflite` or other face-embedding model asset; older identity code in `eve-project/` is not wired into Nova and is not proof of a working feature. Do not guess identities from face detection alone.
4. Improve action/weather recognition with a purpose-trained local model and a held-out image evaluation set. Generic ML Kit image labels are hypotheses, not reliable evidence that a person is eating, exercising, chewing gum, or that it is raining/snowing.
5. Expand Arabic evaluation examples, measure confusion between classes, and add a held-out test set not used for training.
6. Only implement Bluetooth movement after the exact base protocol and available sensors are known. A phone camera and software alone cannot guarantee edge/fall prevention; require physical cliff/obstacle sensors, a hardware emergency stop, and tests on a restrained prototype.
7. Test runtime, memory, battery, thermal behavior, installation, and airplane-mode operation on the actual Redmi 12.

## Target constraints

- No internet permission or remote inference for this milestone.
- Short inputs are bounded to 800 characters in the classifier; the coordinator bounds prompt/input/output size.
- User corrections should be explicit; a correction changes weights but is not proof of general intelligence.
- Emotional labels represent simulated behavior states, not human feelings.
- Camera face detection alone does not identify people or reliably infer emotion/action.
