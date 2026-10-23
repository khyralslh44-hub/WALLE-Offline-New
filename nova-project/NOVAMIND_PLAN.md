# NovaMind Hybrid — from-scratch model milestone

## What is implemented in this branch

- `NovaOwnModel.kt` contains a small multi-layer perceptron (192 hashed text features, a 20-unit hidden layer, and 7 intent classes). Its weights start from deterministic random initialization; it trains locally from the source-controlled Arabic seed examples. It uses no pretrained weights, external model library, cloud API, or network download.
- The model classifies seven broad intents: greeting, question, vision query, memory request, positive feedback, negative feedback, and farewell. It reports confidence and can return UNKNOWN for blank/low-confidence inputs.
- `NovaMind.kt` uses the classifier to select a coarse response style and simulated behavior state. Explicit corrections can update the weights. A compact versioned weight snapshot can be stored via the supplied `NovaMindMemory` interface and restored on the next initialization.
- The unit-test source covers common Arabic examples, snapshot round-trip, correction updates, bounded fallback behavior, and honest capability boundaries.

## Important limitations

This model is **not** a general-purpose language generator. It does not understand arbitrary Arabic text at ChatGPT level and does not generate open-ended answers. The short responses are authored templates selected by the neural classifier. The seed examples are a small, manually written starter dataset; robust generalization will require a larger reviewed dataset and measured error analysis.

The existing `NovaActivity` still instantiates the older `NovaBrain` and is a face/tap/camera interface, not a conversational text UI. Therefore this PR adds the neural module to the project but does **not** yet connect it to the running activity. The memory interface also needs an Android persistent implementation before learning is guaranteed to survive app restarts in the shipped app. Build and test status must be checked from GitHub Actions; real Redmi 12 performance and airplane-mode behavior are not yet measured.

## Next implementation steps

1. Confirm GitHub Actions builds this branch and all unit tests pass.
2. Add a persistent Android implementation of `NovaMindMemory` with a user-visible way to inspect and clear local data; never store raw camera frames or full conversation transcripts by default.
3. Integrate classifier outputs with `NovaActivity`/behavior control, with repetition cooldowns and no unrequested noisy actions.
4. Decide how Nova receives natural-language input while preserving the desired buttonless face UI. No microphone is currently requested by the app.
5. Expand Arabic evaluation examples, measure confusion between classes, and add a hold-out test set not used for training.
6. Test runtime, memory, battery, thermal behavior, and offline operation on the actual Redmi 12.

## Target constraints

- No internet permission or remote inference for this milestone.
- Short inputs are bounded to 800 characters in the classifier; the coordinator bounds prompt/input/output size.
- User corrections should be explicit; a correction changes weights but is not proof of general intelligence.
- Emotional labels represent simulated behavior states, not human feelings.
- Camera face detection alone does not identify people or reliably infer emotion/action.
