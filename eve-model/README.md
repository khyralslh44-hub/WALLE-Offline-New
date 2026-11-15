# EVE private Arabic language model

This pipeline distils Arabic dialogue behavior into EVE's compact two-layer GRU student.
A public, Apache-2.0 open-weight Qwen3-0.6B GGUF model is downloaded **only on the GitHub training runner**.
The teacher is used locally through llama.cpp to generate supervised Arabic target responses.
No OpenAI API, API key, paid endpoint, or teacher-model download on the phone is required.

## What "distillation" means here

The current student has a byte-level UTF-8 tokenizer and a GRU architecture, while Qwen3 is a
Transformer with a different tokenizer. Therefore this implementation uses **sequence-level
supervised distillation**: Qwen3 generates Arabic target answers, then the small GRU is trained
to predict those answers. It does not copy Qwen3 weights or perform direct logits/KL distillation.
The teacher's 429 MB GGUF file is a training-time dependency only and is never packaged in the APK.

## Output

- `dist/eve_private_student.tflite`: compact two-layer GRU-256 student for TensorFlow Lite.
- `dist/eve_private_student.json`: tokenizer, teacher checksum, and training metadata.
- `dist/teacher_corpus.jsonl`: resumable Arabic teacher-generated training corpus.
- `dist/training_checkpoint.keras`: resumable student checkpoint.
- `dist/training_report.json`: validation metrics and TFLite smoke-test result.

## Free training

Run `python train_private_eve_student.py` after installing:
`llama-cpp-python==0.3.36`, `huggingface_hub`, `tensorflow-cpu==2.16.1`, and `numpy`.
A C++ compiler, CMake, and OpenBLAS development libraries are required to build the CPU
llama.cpp Python binding. The workflow installs these dependencies automatically.

Optional environment variables:
- `EVE_TRAIN_EXAMPLES` (default 1,000).
- `EVE_TEACHER_THREADS` (default 4).
- `EVE_TEACHER_WORKERS` (default 1; local inference is serialized).
- `EVE_TEACHER_BATCH_SIZE` (default 50).

The Qwen3 GGUF SHA-256 is pinned and verified before generation. The teacher is used for
training only. The APK receives only the small TFLite student after a successful training
release. The app runtime loads the student from its bundled APK asset; it does not download
the model on first launch.

## Acceptance criteria

A successful CI training run proves only that corpus generation, student training, export,
and the TFLite smoke test succeeded. Before calling EVE's conversation quality ready, we
still need held-out Arabic dialogue/factuality tests and Redmi 12 latency, memory, heat, and
offline tests. A small distilled GRU is not equivalent to Qwen3 or a large foundation model.
