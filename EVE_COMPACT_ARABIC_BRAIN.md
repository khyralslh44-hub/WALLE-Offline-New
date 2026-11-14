# EVE Private Arabic Language Brain

## Target architecture

EVE's generative language student is **EVE-GRU-192**, a compact byte-level recurrent model whose weights are initialized randomly and trained specifically for EVE. A large teacher is used only during dataset creation to provide Arabic explanations, dialogue, reasoning examples, and grounded responses. The teacher's weights, tokenizer, and inference runtime are not copied into EVE.

The Android app uses TensorFlow Lite to run the trained EVE student locally. The student model is a separate file downloaded once with the user's approval and stored in app-private storage. After installation, generation is offline.

## Training pipeline

- Source: `eve-model/train_private_eve_student.py`
- Workflow: `.github/workflows/train-eve-private-model.yml`
- Teacher: configurable OpenAI-compatible chat model (default `gpt-4.1-mini`), used only to create a supervised corpus.
- Student: TensorFlow/Keras GRUCell model, 192 hidden units, 96-dimensional byte embeddings, UTF-8 byte tokenizer, random initialization.
- Export: `eve_private_student.tflite` and metadata JSON.
- Artifact publishing: the workflow uploads the TFLite student to the GitHub release named `eve-private-student`, which the Android app downloads only after the user approves.

The workflow requires an `OPENAI_API_KEY` repository secret. Training has **not** been run merely by adding the workflow. A successful run must produce the release asset before the app's generative student can be installed.

## Android integration

- `EvePrivateStudentLanguageModel.kt` runs the custom TFLite student using TensorFlow Lite.
- `LocalLanguageBrain.kt` calls this runtime and falls back to EVE's existing Arabic intent/composition engine if the private student is not yet installed or inference fails.
- The previous Qwen3/LiteRT-LM runtime and dependency have been removed from the Android app.
- Existing camera, object/face/gesture/pose perception, offline speech, local memory, personality, and game systems remain separate from the language student.

## Honest limitations and acceptance tests

A small GRU trained from scratch is not equivalent to a large foundation model. Its Arabic fluency and reasoning quality depend heavily on the teacher corpus, training time, and evaluation. The first training run is a prototype, not a claim of production-level intelligence.

Before calling EVE ready, verify:
1. Training completes and produces the model release asset.
2. TFLite export opens in Android and generates valid UTF-8 Arabic.
3. A held-out Arabic test set checks factuality, dialogue quality, unsupported questions, and repetition.
4. Offline Android instrumentation passes with Wi-Fi/mobile data disabled after the one-time model download.
5. Redmi 12 tests measure latency, RAM, heat, and battery use.

The APK does not bundle model weights. The model file is downloaded separately; this avoids inflating the APK.