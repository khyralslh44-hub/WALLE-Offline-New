# EVE Private Arabic Language Brain

## Target architecture

EVE uses a compact byte-level GRU language student (two 256-unit recurrent layers, 96-dimensional
embedding, 259-token byte vocabulary). Its training targets come from a local open-weight Qwen3
teacher, but the teacher is not shipped to the phone.

The teacher is downloaded on the GitHub training runner only:
- Teacher: `ggml-org/Qwen3-0.6B-GGUF`, Q4_0 GGUF (Apache-2.0).
- Loader: `llama-cpp-python`/llama.cpp CPU inference.
- Checksum: pinned SHA-256 in `eve-model/train_private_eve_student.py`.
- No OpenAI API key, paid API, or cloud inference call is used.

## Training pipeline

- Source: `eve-model/train_private_eve_student.py`.
- Workflow: `.github/workflows/train-eve-private-model.yml`.
- Distillation type: sequence-level supervised targets. Qwen3 produces Arabic answers;
  the byte-level GRU student learns from those answer tokens. This is not direct logit
  distillation because the student and teacher tokenizers/architectures differ.
- Export: `eve_private_student.tflite` and a JSON metadata file.

The teacher weights remain a build-time dependency and are never copied into the APK.
Only the compact TFLite student is eligible for bundling.

## Android integration

- `EvePrivateStudentLanguageModel.kt` loads the TFLite student from
  `app/src/main/assets/eve_private_student.tflite`, copies it into private app storage,
  and runs it offline.
- It no longer fetches model weights from GitHub or any other network endpoint.
- If a build has no trained asset yet, EVE continues using its existing compact Arabic
  intent/composition engine; it must not claim the generative student is ready.
- Existing camera, perception, memory, personality, and game logic are independent
  of the language student.

## Size and capability constraints

The requested total APK ceiling is 80 MB. The latest measured ARM64 APK was 427,827,473
bytes (about 427.8 MB decimal). The Arabic Vosk model ZIP alone accounted for 333,241,610
bytes. Other vision/gesture models and native libraries also exceed the remaining budget.
Consequently, the language-student change by itself cannot make the whole app fit under 80 MB.
The build workflow reports actual APK size; the Arabic offline speech/vision feature set
must be reduced or replaced with materially smaller models before an 80 MB release can be
honestly claimed. No existing perception/speech model is silently removed by this change.

## Honest limitations and acceptance tests

A small GRU distilled from a 0.6B teacher will retain only a limited portion of the teacher's
behavior. It is a compact Arabic dialogue prototype, not equivalent to Qwen3. A successful
training workflow proves export and numerical TFLite execution only. A held-out Arabic quality
suite and Redmi 12 offline latency/RAM/thermal tests remain required.
